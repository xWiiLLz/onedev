package io.onedev.server.search.entity.issue;


import io.onedev.server.model.Issue;
import io.onedev.server.model.support.issue.fieldspec.BuildChoiceField;
import io.onedev.server.model.Build;
import io.onedev.server.search.entity.EntityCriteria;
import io.onedev.commons.utils.ExplicitException;

/**
 * @see io.onedev.server.search.entity.issue.IssueQueryLexer#IsPrevious
 */
public class IsPrevious extends Operator {
	public int getOperator() {
		return IssueQueryLexer.IsPrevious;
	}

	public boolean matches(Issue issue, Object fieldValue, FieldOperatorCriteria fieldOperatorCriteria) {
		if (fieldOperatorCriteria.getFieldSpec() instanceof BuildChoiceField) {
			Build build = Build.get();
			if (build != null) {
				return build.getProject().equals(issue.getProject())
						&& build.getStreamPreviousNumbers(EntityCriteria.IN_CLAUSE_LIMIT).stream()
								.anyMatch(it -> it.equals(fieldValue));
			} else {
				throw new ExplicitException("No build in query context");
			}
		} else {
			throw new IllegalStateException();
		}
	}
}