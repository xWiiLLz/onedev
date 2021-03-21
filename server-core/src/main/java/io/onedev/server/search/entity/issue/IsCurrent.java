package io.onedev.server.search.entity.issue;


import io.onedev.server.model.Issue;
import io.onedev.server.model.support.issue.fieldspec.BuildChoiceField;
import io.onedev.server.model.Build;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.server.model.support.issue.fieldspec.PullRequestChoiceField;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.support.issue.fieldspec.CommitField;
import io.onedev.server.util.ProjectScopedCommit;

/**
 * @see io.onedev.server.search.entity.issue.IssueQueryLexer#IsCurrent
 */
public class IsCurrent extends Operator {
	public int getOperator() {
		return IssueQueryLexer.IsCurrent;
	}

	public boolean matches(Issue issue, Object fieldValue, FieldOperatorCriteria fieldOperatorCriteria) {
		if (fieldOperatorCriteria.getFieldSpec() instanceof BuildChoiceField) {
			Build build = Build.get();
			if (build != null)
				return build.getProject().equals(issue.getProject()) && build.getId().toString().equals(fieldValue);
			else
				throw new ExplicitException("No build in query context");
		} else if (fieldOperatorCriteria.getFieldSpec() instanceof PullRequestChoiceField) {
			PullRequest request = PullRequest.get();
			if (request != null)
				return request.getTargetProject().equals(issue.getProject())
						&& request.getId().toString().equals(fieldValue);
			else
				throw new ExplicitException("No pull request in query context");
		} else if (fieldOperatorCriteria.getFieldSpec() instanceof CommitField) {
			ProjectScopedCommit commit = ProjectScopedCommit.get();
			if (commit != null)
				return commit.getProject().equals(issue.getProject()) && commit.getCommitId().name().equals(fieldValue);
			else
				throw new ExplicitException("No commit in query context");
		} else {
			throw new IllegalStateException();
		}
	}
}