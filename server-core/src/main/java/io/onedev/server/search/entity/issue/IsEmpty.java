package io.onedev.server.search.entity.issue;


import io.onedev.server.model.Issue;

/**
 * @see io.onedev.server.search.entity.issue.IssueQueryLexer#IsEmpty
 */
public class IsEmpty extends Operator {
	public int getOperator() {
		return IssueQueryLexer.IsEmpty;
	}

	public boolean matches(Issue issue, Object fieldValue, FieldOperatorCriteria fieldOperatorCriteria) {
		return fieldValue == null;
	}
}