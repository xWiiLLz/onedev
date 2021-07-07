package io.onedev.server.search.entity.issue;

import java.nio.channels.IllegalSelectorException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.server.model.Build;
import io.onedev.server.model.Issue;
import io.onedev.server.model.IssueField;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.User;
import io.onedev.server.model.support.issue.fieldspec.BuildChoiceField;
import io.onedev.server.model.support.issue.fieldspec.CommitField;
import io.onedev.server.model.support.issue.fieldspec.PullRequestChoiceField;
import io.onedev.server.search.entity.EntityCriteria;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.util.ProjectScopedCommit;

public class FieldOperatorCriteria extends FieldCriteria {

	private static final long serialVersionUID = 1L;
	
	private Operator operator;
	private final boolean operatorSet;
	
	private final boolean allowMultiple;

	public FieldOperatorCriteria(String name, int operator, boolean allowMultiple) {
		super(name);
		this.setOperator(operator);
		this.operatorSet = true;
		this.allowMultiple = allowMultiple;
	}

	@Override
	protected Predicate getValuePredicate(Join<?, ?> field, CriteriaBuilder builder) {
		Path<?> valueAttribute = field.get(IssueField.PROP_VALUE);
		Path<?> projectAttribute = field.getParent().get(Issue.PROP_PROJECT);		
		if (getOperator() == IssueQueryLexer.IsEmpty) {
			return null;
		} else if (getOperator() == IssueQueryLexer.IsMe) {
			if (User.get() != null)
				return builder.equal(valueAttribute, User.get().getName());
			else
				throw new ExplicitException("Please login to perform this query");
		} else if (getOperator() == IssueQueryLexer.IsCurrent) {
			if (getFieldSpec() instanceof BuildChoiceField) {
				Build build = Build.get();
				if (build != null) { 
					return builder.and(
							builder.equal(projectAttribute, build.getProject()),
							builder.equal(valueAttribute, String.valueOf(build.getNumber())));
				} else {
					throw new ExplicitException("No current build in query context");
				}
			} else if (getFieldSpec() instanceof PullRequestChoiceField) {
				PullRequest request = PullRequest.get();
				if (request != null) {
					return builder.and(
							builder.equal(projectAttribute, request.getTargetProject()),
							builder.equal(valueAttribute, String.valueOf(request.getNumber())));
				} else {
					throw new ExplicitException("No current pull request in query context");
				}
			} else if (getFieldSpec() instanceof CommitField) {
				ProjectScopedCommit commit = ProjectScopedCommit.get();
				if (commit != null) {
					return builder.and(
							builder.equal(projectAttribute, commit.getProject()),
							builder.equal(valueAttribute, commit.getCommitId().name()));
				} else {
					throw new ExplicitException("No current commit in query context");
				}
			} else {
				throw new IllegalStateException();
			}
		} else if (getOperator() == IssueQueryLexer.IsPrevious) {
			if (getFieldSpec() instanceof BuildChoiceField) {
				Build build = Build.get();
				if (build != null) { 
					Collection<Long> streamPreviousNumbers = Build.get().getStreamPreviousNumbers(EntityCriteria.IN_CLAUSE_LIMIT);
					if (!streamPreviousNumbers.isEmpty()) {
						return builder.and(
								builder.equal(projectAttribute, build.getProject()),
								valueAttribute.in(streamPreviousNumbers.stream().map(it->it.toString()).collect(Collectors.toSet())));
					} else {
						return builder.disjunction();
					}
				} else {
					throw new ExplicitException("No current build in query context");
				}
			} else {
				throw new IllegalStateException();
			}
		} else {
			throw new IllegalStateException();
		}
	}

	@Override
	public boolean matches(Issue issue) {
		Object fieldValue = issue.getFieldValue(getFieldName());
		return operator.matches(issue, fieldValue, this);
	}

	@Override
	public String toStringWithoutParens() {
		return quote(getFieldName()) + " " + IssueQuery.getRuleName(getOperator());
	}

	@SuppressWarnings({"unchecked" })
	@Override
	public void fill(Issue issue) {
		if (getOperator() == IssueQueryLexer.IsEmpty) {
			issue.setFieldValue(getFieldName(), null);
		} else if (getOperator() == IssueQueryLexer.IsMe) {
			if (allowMultiple) {
				List<String> valueFromIssue = (List<String>) issue.getFieldValue(getFieldName());
				if (valueFromIssue == null)
					valueFromIssue = new ArrayList<>();
				valueFromIssue.add(SecurityUtils.getUser().getName());
				issue.setFieldValue(getFieldName(), valueFromIssue);
			} else {
				issue.setFieldValue(getFieldName(), SecurityUtils.getUser().getName());
			}
		}
	}

	public void setOperator(int operator) {
		if (this.operatorSet) return;
		if (operator == IssueQueryLexer.IsPrevious)
			this.operator = new IsPrevious();
		if (operator == IssueQueryLexer.IsCurrent)
			this.operator = new IsCurrent();
		if (operator == IssueQueryLexer.IsMe)
			this.operator = new IsMe();
		if (operator == IssueQueryLexer.IsEmpty)
			this.operator = new IsEmpty();
		this.operator = null;		
	}

	public int getOperator() {
		return operator.getOperator();
	}

}
