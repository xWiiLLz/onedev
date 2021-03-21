package io.onedev.server.search.entity.issue;


import io.onedev.server.model.Issue;
import io.onedev.server.model.User;
import java.util.Objects;
import io.onedev.commons.utils.ExplicitException;

/**
 * @see io.onedev.server.search.entity.issue.IssueQueryLexer#IsMe
 */
public class IsMe extends Operator {
	public int getOperator() {
		return IssueQueryLexer.IsMe;
	}

	public boolean matches(Issue issue, Object fieldValue, FieldOperatorCriteria fieldOperatorCriteria) {
		if (User.get() != null)
			return Objects.equals(fieldValue, User.get().getName());
		else
			throw new ExplicitException("Please login to perform this query");
	}
}