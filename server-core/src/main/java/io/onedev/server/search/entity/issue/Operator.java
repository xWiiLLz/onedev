package io.onedev.server.search.entity.issue;


import io.onedev.server.model.Issue;

public abstract class Operator {
	public abstract int getOperator();

	public abstract boolean matches(Issue issue, Object fieldValue, FieldOperatorCriteria fieldOperatorCriteria);
}