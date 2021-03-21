package io.onedev.server.web.component;

import javax.annotation.Nullable;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.extensions.markup.html.repeater.data.table.DataTable;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.IModel;

import io.onedev.server.security.SecurityUtils;
import io.onedev.server.web.util.QuerySaveSupport;

public class QueriableListPanel extends Panel {

	protected Component saveQueryLink;
	protected WebMarkupContainer body;
	protected boolean querySubmitted = true;

	public QueriableListPanel(String id) {
		super(id);
	}

	public QueriableListPanel(String id, IModel<?> model) {
		super(id, model);
	}

	@Nullable
	protected QuerySaveSupport getQuerySaveSupport() {
		return null;
	}

	protected void doQuery(DataTable<?, Void> dataTable, AjaxRequestTarget target) {
		dataTable.setCurrentPage(0);
		target.add(body);
		querySubmitted = true;
		if (SecurityUtils.getUser() != null && getQuerySaveSupport() != null)
			target.add(saveQueryLink);
	}
}
