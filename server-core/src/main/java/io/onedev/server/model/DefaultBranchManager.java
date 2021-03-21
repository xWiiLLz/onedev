package io.onedev.server.model;


import com.google.common.base.Optional;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Constants;
import com.google.common.base.Optional;
import org.eclipse.jgit.lib.Repository;
import java.io.IOException;
import org.eclipse.jgit.lib.RefUpdate;
import io.onedev.server.git.GitUtils;
import java.io.Serializable;

public class DefaultBranchManager implements Serializable {
	private transient Optional<String> defaultBranchOptional;

	@Nullable
	public String getDefaultBranch(RepositoryManager thisRepositoryManager, Project project) {
		if (defaultBranchOptional == null) {
			try {
				Ref headRef = thisRepositoryManager.getRepository(project).findRef("HEAD");
				if (headRef != null && headRef.isSymbolic()
						&& headRef.getTarget().getName().startsWith(Constants.R_HEADS)
						&& headRef.getObjectId() != null) {
					defaultBranchOptional = Optional.of(Repository.shortenRefName(headRef.getTarget().getName()));
				} else {
					defaultBranchOptional = Optional.absent();
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return defaultBranchOptional.orNull();
	}

	public void setDefaultBranch(String defaultBranchName, RepositoryManager thisRepositoryManager, Project project) {
		RefUpdate refUpdate = GitUtils.getRefUpdate(thisRepositoryManager.getRepository(project), "HEAD");
		GitUtils.linkRef(refUpdate, GitUtils.branch2ref(defaultBranchName));
		defaultBranchOptional = null;
	}
}