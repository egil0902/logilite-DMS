/******************************************************************************
 * Copyright (C) 2016 Logilite Technologies LLP								  *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 *****************************************************************************/

package org.idempiere.dms.factories;

import org.idempiere.dms.factories.IMountingStrategy;
import org.idempiere.dms.storage.RelationalContentManager;
import org.idempiere.dms.factories.IMountingFactory;

public class DefaultMountingFactory implements IMountingFactory
{

	@Override
	public IMountingStrategy getMountingStrategy(String contentManagerType, String Table_Name)
	{
		if (RelationalContentManager.KEY.equals(contentManagerType))
			return new DefaultMountingStrategy();
		return null;
	}
}
