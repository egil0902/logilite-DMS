package com.logilite.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.IArchiveStore;
import org.compiere.model.MArchive;
import org.compiere.model.MAttributeInstance;
import org.compiere.model.MAttributeSetInstance;
import org.compiere.model.MStorageProvider;
import org.compiere.model.MSysConfig;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.util.CCache;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Util;
import org.idempiere.dms.factories.IContentManager;
import org.idempiere.dms.factories.IMountingStrategy;
import org.idempiere.dms.factories.IThumbnailGenerator;
import org.idempiere.dms.factories.Utils;
import org.idempiere.model.FileStorageUtil;
import org.idempiere.model.IFileStorageProvider;
import org.idempiere.model.MDMSAssociation;
import org.idempiere.model.MDMSAssociationType;
import org.idempiere.model.MDMSContent;
import org.idempiere.model.MDMSMimeType;
import org.idempiere.model.X_DMS_Content;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.zkoss.util.media.AMedia;

import com.logilite.search.factory.IIndexSearcher;
import com.logilite.search.factory.ServiceUtils;

public class ArchiveDMS implements IArchiveStore
{
	private IFileStorageProvider			fileStorgProvider				= null;
	private IContentManager					contentManager					= null;
	private IIndexSearcher					indexSeracher					= null;
	private IThumbnailGenerator				thumbnailGenerator				= null;
	private String							DMS_ARCHIVE_CONTENT_TYPE		= "ArchiveDocument";
	private String							DMS_ATTRIBUTE_BUSINESS_PARTNER	= "C_BPartner_ID";
	private String							DMS_ATTRIBUTE_DOCUMENT_TYPE		= "C_DocType_ID";
	private String							DMS_ATTRIBUTE_SET_NAME			= "Archive Document";
	private String							DMS_ATTRIBUTE_DOCUMENT_STATUS	= "DocStatus";
	private String							DMS_ATTRIBUTE_PROCESS			= "AD_Process_ID";
	private String							DMS_ATTRIBUTE_CREATED_DATE		= "Created";

	private static CCache<String, Integer>	cTypeCache						= new CCache<String, Integer>(
			"ArchiveCache", 100);
	private static final CLogger			log								= CLogger.getCLogger(ArchiveDMS.class);

	@Override
	public byte[] loadLOBData(MArchive archive, MStorageProvider prov)
	{
		byte[] data = archive.getByteData();
		if (data == null)
		{
			return null;
		}

		fileStorgProvider = FileStorageUtil.get(Env.getAD_Client_ID(Env.getCtx()), false);
		if (fileStorgProvider == null)
			throw new AdempiereException("Storage provider is not define on clientInfo.");

		contentManager = Utils.getContentManager(Env.getAD_Client_ID(Env.getCtx()));
		if (contentManager == null)
			throw new AdempiereException("Content manager is not found.");

		final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

		try
		{
			final DocumentBuilder builder = factory.newDocumentBuilder();
			final Document document = builder.parse(new ByteArrayInputStream(data));
			final NodeList entries = document.getElementsByTagName("archive");
			if (entries.getLength() != 1)
			{
				log.severe("no archive entry found");
				return null;
			}

			final Node archiveNode = entries.item(0);
			NodeList list = archiveNode.getChildNodes();
			if (list.getLength() > 0)
			{
				Node dmsContentNode = list.item(0);
				if (dmsContentNode != null)
				{
					String dmsContentID = dmsContentNode.getFirstChild().getNodeValue();
					if (!Util.isEmpty(dmsContentID))
					{
						int contentID = 0;
						try
						{
							contentID = Integer.parseInt(dmsContentID);
						}
						catch (Exception e)
						{
							return null;
						}
						MDMSContent mdmsContent = new MDMSContent(Env.getCtx(), contentID, null);
						File file = fileStorgProvider.getFile(contentManager.getPath(mdmsContent));
						if (file.exists())
						{
							// read files into byte[]
							final byte[] dataEntry = new byte[(int) file.length()];
							try
							{
								final FileInputStream fileInputStream = new FileInputStream(file);
								fileInputStream.read(dataEntry);
								fileInputStream.close();
							}
							catch (FileNotFoundException e)
							{
								log.severe("File Not Found.");
								e.printStackTrace();
							}
							catch (IOException e1)
							{
								log.severe("Error Reading The File.");
								e1.printStackTrace();
							}
							return dataEntry;
						}
						else
						{
							log.severe("file not found: " + file.getAbsolutePath());
							return null;
						}

					}
				}

			}
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, e.getMessage(), e);
		}
		return null;
	}

	@Override
	public void save(MArchive archive, MStorageProvider prov, byte[] inflatedData)
	{
		try
		{
			fileStorgProvider = FileStorageUtil.get(Env.getAD_Client_ID(Env.getCtx()), false);
			if (fileStorgProvider == null)
				throw new AdempiereException("Storage provider is not define on clientInfo.");

			contentManager = Utils.getContentManager(Env.getAD_Client_ID(Env.getCtx()));
			if (contentManager == null)
				throw new AdempiereException("Content manager is not found.");

			indexSeracher = ServiceUtils.getIndexSearcher(Env.getAD_Client_ID(Env.getCtx()));
			if (indexSeracher == null)
				throw new AdempiereException("Solr Index server is not found.");

			int cTypeID = getContentTypeID(DMS_ARCHIVE_CONTENT_TYPE, 0);

			Integer tableID = archive.getAD_Table_ID();
			String tableName = MTable.getTableName(Env.getCtx(), tableID);
			int recordID = archive.getRecord_ID();

			IMountingStrategy mountingStrategy = null;
			MDMSContent mountingParent = null;
			if (Util.isEmpty(tableName) || recordID <= 0)
			{
				if (tableName == null)
					tableName = "";
				// Generate Mounting Parent
				String mountingArchiveBaseName = MSysConfig.getValue("DMS_MOUNTING_ARCHIVE_BASE", "Archive");
				Utils.initiateMountingContent(mountingArchiveBaseName, tableName, recordID, tableID);
				mountingStrategy = Utils.getMountingStrategy(tableName);
				mountingParent = mountingStrategy.getMountingParentForArchive();
			}
			else
			{
				// Generate Mounting Parent
				Utils.initiateMountingContent(tableName, recordID, tableID);
				mountingStrategy = Utils.getMountingStrategy(tableName);
				mountingParent = mountingStrategy.getMountingParent(tableName, recordID);
			}
			// Generate File
			File file = generateFile(archive, inflatedData);

			// Create DMS Content
			MDMSContent dmsContent = new MDMSContent(Env.getCtx(), 0, archive.get_TrxName());
			dmsContent.setName(file.getName());
			dmsContent.setValue(file.getName());
			dmsContent.setDMS_MimeType_ID(Utils.getMimeTypeID(new AMedia(file, "application/pdf", null)));
			dmsContent.setParentURL(contentManager.getPath(mountingParent));
			dmsContent.setContentBaseType(X_DMS_Content.CONTENTBASETYPE_Content);
			dmsContent.setIsMounting(true);
			dmsContent.setDMS_FileSize(Utils.readableFileSize(file.length()));
			if (cTypeID > 0)
				dmsContent.setDMS_ContentType_ID(cTypeID);
			dmsContent.saveEx();

			// Create Attributes
			addAttributes(tableID, recordID, dmsContent, archive);

			// Create DMS Association
			MDMSAssociation dmsAssociation = new MDMSAssociation(Env.getCtx(), 0, archive.get_TrxName());
			dmsAssociation.setDMS_Content_ID(dmsContent.getDMS_Content_ID());
			dmsAssociation.setAD_Table_ID(tableID);
			dmsAssociation.setRecord_ID(recordID);
			dmsAssociation.setDMS_AssociationType_ID(MDMSAssociationType.getVersionType(true));
			if (mountingParent != null)
				dmsAssociation.setDMS_Content_Related_ID(mountingParent.getDMS_Content_ID());
			dmsAssociation.saveEx();

			// Upload file to DMS
			fileStorgProvider.writeBLOB(fileStorgProvider.getBaseDirectory(contentManager.getPath(dmsContent)),
					inflatedData, dmsContent);

			MDMSMimeType mimeType = new MDMSMimeType(Env.getCtx(), dmsContent.getDMS_MimeType_ID(),
					archive.get_TrxName());

			// Generate Thumbnail Image
			thumbnailGenerator = Utils.getThumbnailGenerator(mimeType.getMimeType());
			if (thumbnailGenerator != null)
				thumbnailGenerator.addThumbnail(dmsContent, file, null);

			archive.setByteData(generateEntry(dmsContent, dmsAssociation));
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, e.getMessage(), e);
		}
	}

	public byte[] generateEntry(MDMSContent dmsContent, MDMSAssociation dmsAssociation)
	{
		final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		try
		{
			// create xml entry
			final DocumentBuilder builder = factory.newDocumentBuilder();
			final Document document = builder.newDocument();
			final Element root = document.createElement("archive");
			document.appendChild(root);
			document.setXmlStandalone(true);
			Element content = document.createElement("DMS_Content_ID");
			content.appendChild(document.createTextNode(String.valueOf(dmsContent.get_ID())));
			root.appendChild(content);

			Element association = document.createElement("DMS_Association_ID");
			association.appendChild(document.createTextNode(String.valueOf(dmsAssociation.get_ID())));
			root.appendChild(association);

			final Source source = new DOMSource(document);
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			final Result result = new StreamResult(bos);
			final Transformer xformer = TransformerFactory.newInstance().newTransformer();
			xformer.transform(source, result);
			final byte[] xmlData = bos.toByteArray();
			return xmlData;
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, e.getMessage());
			return "Error Message".getBytes();
		}
	}

	public File generateFile(MArchive archive, byte[] inflatedData) throws Exception
	{
		String timeStamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
		String archiveName = archive.getName();
		int recordID = archive.getRecord_ID();
		if (Util.isEmpty(archiveName))
		{
			archiveName = "";
			if (recordID > 0)
				archiveName += recordID;
		}
		else
		{
			archiveName = archiveName.trim();
			archiveName = archiveName.replaceAll(" ", "");
			if (recordID > 0)
				archiveName = recordID + " " + archiveName;
		}

		String fileName = archiveName + "_" + timeStamp + ".pdf";
		File file = new File(fileName);
		FileOutputStream fileOuputStream = null;
		try
		{
			fileOuputStream = new FileOutputStream(file);
			fileOuputStream.write(inflatedData);
		}
		finally
		{
			if (file != null)
			{
				fileOuputStream.flush();
				fileOuputStream.close();
			}
		}
		return file;
	}

	public static int getContentTypeID(String contentType, int clientID)
	{
		Integer cTypeID = cTypeCache.get(contentType);
		if (cTypeID == null || cTypeID <= 0)
		{
			cTypeID = DB.getSQLValue(null,
					"SELECT DMS_ContentType_ID FROM DMS_ContentType WHERE IsActive='Y' AND Value = ? AND AD_Client_ID = ?",
					contentType, clientID);
			if (cTypeID > 0)
				cTypeCache.put(contentType, cTypeID);
		}
		return cTypeID;
	}

	public void addAttributes(int tableID, int recordID, MDMSContent dmsContent, MArchive archive)
	{
		if (dmsContent != null)
		{
			PO po = null;
			if (tableID > 0 && recordID > 0)
				po = MTable.get(Env.getCtx(), tableID).getPO(recordID, null);

			int attributeSetID = getAttributeSetID(DMS_ATTRIBUTE_SET_NAME);
			int bpartnerAttributeID = getAttributeID(DMS_ATTRIBUTE_BUSINESS_PARTNER);
			int docStatusAttributeID = getAttributeID(DMS_ATTRIBUTE_DOCUMENT_STATUS);
			int docTypeAttributeID = getAttributeID(DMS_ATTRIBUTE_DOCUMENT_TYPE);
			int createdAttributeID = getAttributeID(DMS_ATTRIBUTE_CREATED_DATE);
			int processAttributeID = getAttributeID(DMS_ATTRIBUTE_PROCESS);

			int bPartnerValue = 0;
			String docStatusValue = "";
			int docTypeValue = 0;

			if (po != null)
			{
				if (bpartnerAttributeID >= 0)
					bPartnerValue = po.get_ValueAsInt(DMS_ATTRIBUTE_BUSINESS_PARTNER);

				if (docStatusAttributeID >= 0)
				{
					docStatusValue = po.get_ValueAsString(DMS_ATTRIBUTE_DOCUMENT_STATUS);
				}

				if (docTypeAttributeID >= 0)
					docTypeValue = po.get_ValueAsInt(DMS_ATTRIBUTE_DOCUMENT_TYPE);
			}

			MAttributeSetInstance asi = new MAttributeSetInstance(Env.getCtx(), 0, attributeSetID, null);
			asi.save();

			MAttributeInstance attributeInstance = null;
			if (bPartnerValue > 0)
			{
				attributeInstance = new MAttributeInstance(Env.getCtx(), bpartnerAttributeID, asi.get_ID(),
						bPartnerValue, null);
				attributeInstance.save();
			}

			if (docTypeValue > 0)
			{
				attributeInstance = new MAttributeInstance(Env.getCtx(), docTypeAttributeID, asi.get_ID(), docTypeValue,
						null);
				attributeInstance.save();
			}

			if (!Util.isEmpty(docStatusValue))
			{
				attributeInstance = new MAttributeInstance(Env.getCtx(), docStatusAttributeID, asi.get_ID(),
						docStatusValue, null);
				attributeInstance.save();
			}

			if (createdAttributeID > 0)
			{
				attributeInstance = new MAttributeInstance(Env.getCtx(), createdAttributeID, asi.get_ID(),
						archive.getCreated(), null);
				attributeInstance.save();
			}

			if (processAttributeID > 0)
			{
				if (archive.getAD_Process_ID() > 0)
				{
					attributeInstance = new MAttributeInstance(Env.getCtx(), processAttributeID, asi.get_ID(),
							archive.getAD_Process_ID(), null);
					attributeInstance.save();
				}
			}

			dmsContent.setM_AttributeSetInstance_ID(asi.get_ID());
			dmsContent.save();
		}
	}

	@Override
	public boolean deleteArchive(MArchive archive, MStorageProvider prov)
	{
		return true;
	}

	public int getAttributeID(String AttributeName)
	{
		Integer attributeID = cTypeCache.get(AttributeName);
		if (attributeID == null || attributeID <= 0)
		{
			String sql = "SELECT M_Attribute_ID FROM M_Attribute WHERE IsActive='Y' AND Name = ? AND AD_Client_ID = 0";
			attributeID = DB.getSQLValue(null, sql, AttributeName);
			if (attributeID > 0)
				cTypeCache.put(AttributeName, attributeID);
		}
		return attributeID;
	}

	public int getAttributeSetID(String AttributeSetName)
	{
		Integer attributeSetID = cTypeCache.get(AttributeSetName);
		if (attributeSetID == null || attributeSetID <= 0)
		{
			String sql = "SELECT M_AttributeSet_ID FROM M_AttributeSet WHERE IsActive='Y' AND Name = ? AND AD_Client_ID = 0";
			attributeSetID = DB.getSQLValue(null, sql, AttributeSetName);
			if (attributeSetID > 0)
				cTypeCache.put(AttributeSetName, attributeSetID);
		}
		return attributeSetID;
	}

	public int getAttributeValueID(String AttributeValue)
	{
		Integer attributeValueID = cTypeCache.get(AttributeValue);
		if (attributeValueID == null || attributeValueID <= 0)
		{
			String sql = "SELECT M_AttributeValue_ID FROM M_AttributeValue WHERE IsActive='Y' AND Value = ? AND AD_Client_ID = 0";
			attributeValueID = DB.getSQLValue(null, sql, AttributeValue);
			if (attributeValueID > 0)
				cTypeCache.put(AttributeValue, attributeValueID);
		}
		return attributeValueID;
	}

}
