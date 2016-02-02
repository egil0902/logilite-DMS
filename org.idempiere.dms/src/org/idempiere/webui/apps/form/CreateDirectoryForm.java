package org.idempiere.webui.apps.form;

import java.io.File;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.webui.apps.AEnv;
import org.adempiere.webui.component.ConfirmPanel;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Panel;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.component.Window;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.idempiere.dms.storage.DmsUtility;
import org.idempiere.model.MDMS_Association;
import org.idempiere.model.MDMS_Content;
import org.idempiere.model.X_DMS_Content;
import org.zkoss.zk.ui.WrongValueException;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Hbox;
import org.zkoss.zul.North;
import org.zkoss.zul.Separator;
import org.zkoss.zul.South;

public class CreateDirectoryForm extends Window implements EventListener<Event>
{

	/**
	 * 
	 */
	private static final long		serialVersionUID	= 4397569198011705268L;
	protected static final CLogger	log					= CLogger.getCLogger(CreateDirectoryForm.class);

	private Borderlayout			mainLayout			= new Borderlayout();
	private Panel					parameterPanel		= new Panel();
	private ConfirmPanel			confirmPanel		= new ConfirmPanel(true, false, false, false, false, false);
	private Label					lblDir				= new Label(Msg.translate(Env.getCtx(), "Enter Directory Name"));
	private Textbox					txtboxDirectory		= new Textbox();
	private File					file				= null;
	private MDMS_Content			mdms_content		= null;
	private WDocumentViewer			wDocumentViewer;
	private boolean					isGridButton;

	public CreateDirectoryForm(MDMS_Content dms_content, WDocumentViewer wDocumentViewer, boolean isGridButton)
	{
		try
		{
			this.mdms_content = dms_content;
			this.wDocumentViewer = wDocumentViewer;
			this.isGridButton = isGridButton;
			init();
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, "Render Component Problem");
			throw new AdempiereException("Render Component Problem : " + e.getLocalizedMessage());
		}
	}

	public void init() throws Exception
	{
		this.setHeight("150px");
		this.setWidth("500px");
		this.setTitle(Msg.getMsg(Env.getCtx(), "Create Directory"));
		mainLayout.setParent(this);
		mainLayout.setHflex("1");
		mainLayout.setVflex("1");

		lblDir.setValue(Msg.getMsg(Env.getCtx(), "Enter Directory Name") + ": ");
		lblDir.setStyle("padding-left: 5px");
		txtboxDirectory.setWidth("300px");
		txtboxDirectory.setFocus(true);

		North north = new North();
		north.setParent(mainLayout);
		mainLayout.appendChild(north);
		north.appendChild(parameterPanel);

		Hbox hbox = new Hbox();
		hbox.setAlign("center");
		hbox.setPack("start");
		hbox.appendChild(lblDir);
		hbox.appendChild(txtboxDirectory);

		parameterPanel.setStyle("padding: 5px");
		parameterPanel.appendChild(hbox);
		Separator separator = new Separator();
		separator.setOrient("horizontal");
		separator.setBar(true);
		separator.setStyle("padding-top: 40px");
		parameterPanel.appendChild(separator);

		South south = new South();
		south.setSclass("dialog-footer");
		south.setParent(mainLayout);
		mainLayout.appendChild(south);
		south.appendChild(confirmPanel);

		confirmPanel.addActionListener(Events.ON_CLICK, this);
		AEnv.showCenterScreen(this);
	}

	@Override
	public void onEvent(Event event) throws Exception
	{
		log.info(event.getName());

		if (event.getTarget().getId().equals(ConfirmPanel.A_CANCEL))
		{
			this.detach();
		}
		if (event.getTarget().getId().equals(ConfirmPanel.A_OK))
		{
			String fillMandatory = Msg.translate(Env.getCtx(), "FillMandatory");
			String dirName = txtboxDirectory.getValue();
			if (dirName == null || dirName.equals(""))
			{
				throw new WrongValueException(txtboxDirectory, fillMandatory);
			}
			try
			{
				File rootFolder = null;
				if (mdms_content.getParentURL() == null)
					rootFolder = new File(System.getProperty("user.dir") + File.separator + mdms_content.getName());
				else
					rootFolder = new File(System.getProperty("user.dir") + File.separator + mdms_content.getParentURL()
							+ File.separator + mdms_content.getName());

				if (!rootFolder.exists())
					rootFolder.mkdirs();

				file = new File(rootFolder + File.separator + dirName);
				if (!file.exists())
					file.mkdir();
				else
					throw new AdempiereException(Msg.getMsg(Env.getCtx(), "Directory already exists."));

				MDMS_Content content = new MDMS_Content(Env.getCtx(), 0, null);
				content.setDMS_MimeType_ID(DmsUtility.getMimeTypeID(null));
				content.setName(dirName);
				content.setDMS_ContentType_ID(DmsUtility.getContentTypeID());
				content.setDMS_Status_ID(DmsUtility.getStatusID());

				if (mdms_content.getParentURL() == null)
				{
					content.setParentURL(File.separator + mdms_content.getName());
				}
				else
					content.setParentURL(mdms_content.getParentURL() + File.separator + mdms_content.getName());

				content.setValue(dirName);
				content.setContentBaseType(X_DMS_Content.CONTENTBASETYPE_Directory);
				content.saveEx();

				MDMS_Association dmsAssociation = new MDMS_Association(Env.getCtx(), 0, null);
				dmsAssociation.setDMS_Content_ID(content.getDMS_Content_ID());
				dmsAssociation.setDMS_Content_Related_ID(mdms_content.getDMS_Content_ID());
				dmsAssociation.setDMS_AssociationType_ID(DmsUtility.getVersionID());
				dmsAssociation.saveEx();
				this.detach();

				wDocumentViewer.onOk(isGridButton, mdms_content);
			}
			catch (Exception e)
			{
				log.log(Level.SEVERE, "Directory is not created");
				throw new AdempiereException(Msg.getMsg(Env.getCtx(), "Directory is not created"));
			}
		}
	}
}