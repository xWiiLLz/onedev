package io.onedev.server.model;


import javax.persistence.Lob;
import javax.persistence.Column;
import com.fasterxml.jackson.annotation.JsonView;
import io.onedev.server.util.jackson.DefaultView;
import java.util.ArrayList;
import io.onedev.server.model.support.BranchProtection;
import io.onedev.server.model.support.TagProtection;
import io.onedev.server.util.usermatch.UserMatch;
import io.onedev.server.util.patternset.PatternSet;
import io.onedev.server.util.match.PathMatcher;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import io.onedev.server.model.support.FileProtection;
import io.onedev.server.util.reviewrequirement.ReviewRequirement;
import org.eclipse.jgit.lib.ObjectId;
import java.util.Map;
import java.io.Serializable;

public class TagBranchProtection implements Serializable {
	private ArrayList<BranchProtection> branchProtections = new ArrayList<>();
	private ArrayList<TagProtection> tagProtections = new ArrayList<>();

	public ArrayList<BranchProtection> getBranchProtections() {
		return branchProtections;
	}

	public void setBranchProtections(ArrayList<BranchProtection> branchProtections) {
		this.branchProtections = branchProtections;
	}

	public ArrayList<TagProtection> getTagProtections() {
		return tagProtections;
	}

	public void setTagProtections(ArrayList<TagProtection> tagProtections) {
		this.tagProtections = tagProtections;
	}

	public TagProtection getTagProtection(String tagName, User user, Project project) {
		boolean noCreation = false;
		boolean noDeletion = false;
		boolean noUpdate = false;
		for (TagProtection protection : tagProtections) {
			if (protection.isEnabled() && UserMatch.parse(protection.getUserMatch()).matches(project, user)
					&& PatternSet.parse(protection.getTags()).matches(new PathMatcher(), tagName)) {
				noCreation = noCreation || protection.isPreventCreation();
				noDeletion = noDeletion || protection.isPreventDeletion();
				noUpdate = noUpdate || protection.isPreventUpdate();
			}
		}
		TagProtection protection = new TagProtection();
		protection.setPreventCreation(noCreation);
		protection.setPreventDeletion(noDeletion);
		protection.setPreventUpdate(noUpdate);
		return protection;
	}

	public BranchProtection getBranchProtection(String branchName, @Nullable User user, Project project) {
		boolean noCreation = false;
		boolean noDeletion = false;
		boolean noForcedPush = false;
		Set<String> jobNames = new HashSet<>();
		List<FileProtection> fileProtections = new ArrayList<>();
		ReviewRequirement reviewRequirement = ReviewRequirement.parse(null, true);
		for (BranchProtection protection : branchProtections) {
			if (protection.isEnabled() && UserMatch.parse(protection.getUserMatch()).matches(project, user)
					&& PatternSet.parse(protection.getBranches()).matches(new PathMatcher(), branchName)) {
				noCreation = noCreation || protection.isPreventCreation();
				noDeletion = noDeletion || protection.isPreventDeletion();
				noForcedPush = noForcedPush || protection.isPreventForcedPush();
				jobNames.addAll(protection.getJobNames());
				fileProtections.addAll(protection.getFileProtections());
				reviewRequirement.mergeWith(protection.getParsedReviewRequirement());
			}
		}
		BranchProtection protection = new BranchProtection();
		protection.setFileProtections(fileProtections);
		protection.setJobNames(new ArrayList<>(jobNames));
		protection.setPreventCreation(noCreation);
		protection.setPreventDeletion(noDeletion);
		protection.setPreventForcedPush(noForcedPush);
		protection.setParsedReviewRequirement(reviewRequirement);
		return protection;
	}

	public boolean isReviewRequiredForModification(User user, String branch, @Nullable String file, Project project) {
		return getBranchProtection(branch, user, project).isReviewRequiredForModification(user, project, branch, file);
	}

	public boolean isReviewRequiredForPush(User user, String branch, ObjectId oldObjectId, ObjectId newObjectId,
			Map<String, String> gitEnvs, Project project) {
		return getBranchProtection(branch, user, project).isReviewRequiredForPush(user, project, branch, oldObjectId,
				newObjectId, gitEnvs);
	}

	public boolean isBuildRequiredForModification(User user, String branch, @Nullable String file, Project project) {
		return getBranchProtection(branch, user, project).isBuildRequiredForModification(project, branch, file);
	}

	public boolean isBuildRequiredForPush(User user, String branch, ObjectId oldObjectId, ObjectId newObjectId,
			Map<String, String> gitEnvs, Project project) {
		return getBranchProtection(branch, user, project).isBuildRequiredForPush(project, oldObjectId, newObjectId,
				gitEnvs);
	}
}