package org.idempiere.webui.apps.form;

import org.adempiere.webui.panel.ADForm;

public class WDocumentExplorer extends ADForm
{

	/**
	 * 
	 */
	private static final long	serialVersionUID	= 4615438048526323068L;

	@Override
	protected void initForm()
	{
		this.setHeight("100%");
		this.setWidth("100%");

		WDocumentViewer docviewer = new WDocumentViewer();
		this.appendChild(docviewer);
	}

}
