package io.onedev.server.model;


import java.util.Map;
import org.eclipse.jgit.lib.ObjectId;
import java.util.Collection;
import io.onedev.server.util.StatusInfo;
import java.util.HashMap;
import javax.annotation.Nullable;
import io.onedev.server.entitymanager.BuildManager;
import io.onedev.server.model.Build.Status;
import io.onedev.server.OneDev;
import com.google.common.collect.Sets;
import com.google.common.base.Preconditions;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;
import java.io.Serializable;

public class CommitStatusManager implements Serializable {
	private transient Map<ObjectId, Map<String, Collection<StatusInfo>>> commitStatusCache;

	public Map<ObjectId, Map<String, Collection<StatusInfo>>> getCommitStatusCache() {
		if (commitStatusCache == null)
			commitStatusCache = new HashMap<>();
		return commitStatusCache;
	}

	public void cacheCommitStatus(Map<ObjectId, Map<String, Collection<StatusInfo>>> commitStatuses) {
		getCommitStatusCache().putAll(commitStatuses);
	}

	public Map<String, Status> getCommitStatus(ObjectId commitId, @Nullable PullRequest request,
			@Nullable String refName, Project project) {
		Map<String, Collection<StatusInfo>> commitStatusInfos = getCommitStatusCache().get(commitId);
		if (commitStatusInfos == null) {
			BuildManager buildManager = OneDev.getInstance(BuildManager.class);
			commitStatusInfos = buildManager.queryStatus(project, Sets.newHashSet(commitId)).get(commitId);
			getCommitStatusCache().put(commitId, Preconditions.checkNotNull(commitStatusInfos));
		}
		Map<String, Status> commitStatus = new HashMap<>();
		for (Map.Entry<String, Collection<StatusInfo>> entry : commitStatusInfos.entrySet()) {
			Collection<Status> statuses = new ArrayList<>();
			for (StatusInfo statusInfo : entry.getValue()) {
				if ((refName == null || refName.equals(statusInfo.getRefName()))
						&& Objects.equals(PullRequest.idOf(request), statusInfo.getRequestId())) {
					statuses.add(statusInfo.getStatus());
				}
			}
			commitStatus.put(entry.getKey(), Status.getOverallStatus(statuses));
		}
		return commitStatus;
	}
}