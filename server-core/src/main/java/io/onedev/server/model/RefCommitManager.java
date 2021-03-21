package io.onedev.server.model;



import java.util.Map;
import com.google.common.base.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.lib.Ref;
import javax.annotation.Nullable;
import java.util.HashMap;
import com.google.common.base.Optional;
import io.onedev.server.git.GitUtils;
import io.onedev.server.git.exception.ObjectNotFoundException;
import org.eclipse.jgit.revwalk.LastCommitsOfChildren;
import org.eclipse.jgit.revwalk.LastCommitsOfChildren.Value;
import java.io.File;
import io.onedev.server.OneDev;
import io.onedev.server.storage.StorageManager;
import java.util.concurrent.locks.ReadWriteLock;
import io.onedev.commons.utils.LockUtils;
import java.io.IOException;
import java.util.Set;
import java.util.HashSet;
import io.onedev.commons.utils.FileUtils;
import org.apache.commons.lang3.SerializationUtils;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RefUpdate;
import java.util.List;
import io.onedev.server.git.RefInfo;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.Collections;
import java.io.Serializable;

public class RefCommitManager implements Serializable {
	private transient DefaultBranchManager defaultBranchManager = new DefaultBranchManager();
	private transient Map<String, Optional<ObjectId>> objectIdCache;
	private transient Map<ObjectId, Optional<RevCommit>> commitCache;
	private transient Map<String, Optional<Ref>> refCache;
	private transient Optional<RevCommit> lastCommitHolder;

	public void cacheObjectId(String revision, @Nullable ObjectId objectId) {
		if (objectIdCache == null)
			objectIdCache = new HashMap<>();
		objectIdCache.put(revision, Optional.fromNullable(objectId));
	}

	/**
	* Get cached object id of specified revision.
	* @param revision revision to resolve object id for
	* @param mustExist true to have the method throwing exception instead  of returning null if the revision does not exist
	* @return object id of specified revision, or <tt>null</tt> if revision  does not exist and mustExist is specified as false
	*/
	@Nullable
	public ObjectId getObjectId(String revision, boolean mustExist, RepositoryManager thisRepositoryManager,
			Project project) {
		if (objectIdCache == null)
			objectIdCache = new HashMap<>();
		Optional<ObjectId> optional = objectIdCache.get(revision);
		if (optional == null) {
			optional = Optional.fromNullable(GitUtils.resolve(thisRepositoryManager.getRepository(project), revision));
			objectIdCache.put(revision, optional);
		}
		if (mustExist && !optional.isPresent())
			throw new ObjectNotFoundException("Unable to find object '" + revision + "'");
		return optional.orNull();
	}

	public LastCommitsOfChildren getLastCommitsOfChildren(String revision, @Nullable String path,
			RepositoryManager thisRepositoryManager, Project project) {
		if (path == null)
			path = "";
		final File cacheDir = new File(OneDev.getInstance(StorageManager.class).getProjectInfoDir(project.getId()),
				"last_commits/" + path + "/onedev_last_commits");
		final ReadWriteLock lock;
		try {
			lock = LockUtils.getReadWriteLock(cacheDir.getCanonicalPath());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		final Set<ObjectId> commitIds = new HashSet<>();
		lock.readLock().lock();
		try {
			if (cacheDir.exists()) {
				for (String each : cacheDir.list())
					commitIds.add(ObjectId.fromString(each));
			}
		} finally {
			lock.readLock().unlock();
		}
		org.eclipse.jgit.revwalk.LastCommitsOfChildren.Cache cache;
		if (!commitIds.isEmpty()) {
			cache = new org.eclipse.jgit.revwalk.LastCommitsOfChildren.Cache() {
				@SuppressWarnings("unchecked")
				@Override
				public Map<String, Value> getLastCommitsOfChildren(ObjectId commitId) {
					if (commitIds.contains(commitId)) {
						lock.readLock().lock();
						try {
							byte[] bytes = FileUtils.readFileToByteArray(new File(cacheDir, commitId.name()));
							return (Map<String, Value>) SerializationUtils.deserialize(bytes);
						} catch (IOException e) {
							throw new RuntimeException(e);
						} finally {
							lock.readLock().unlock();
						}
					} else {
						return null;
					}
				}
			};
		} else {
			cache = null;
		}
		final AnyObjectId commitId = getObjectId(revision, true, thisRepositoryManager, project);
		long time = System.currentTimeMillis();
		LastCommitsOfChildren lastCommits = new LastCommitsOfChildren(thisRepositoryManager.getRepository(project),
				commitId, path, cache);
		long elapsed = System.currentTimeMillis() - time;
		if (elapsed > Project.LAST_COMMITS_CACHE_THRESHOLD) {
			lock.writeLock().lock();
			try {
				if (!cacheDir.exists())
					FileUtils.createDir(cacheDir);
				FileUtils.writeByteArrayToFile(new File(cacheDir, commitId.name()),
						SerializationUtils.serialize(lastCommits));
			} catch (IOException e) {
				throw new RuntimeException(e);
			} finally {
				lock.writeLock().unlock();
			}
		}
		return lastCommits;
	}

	@Nullable
	public RevCommit getRevCommit(ObjectId revId, boolean mustExist, RepositoryManager thisRepositoryManager,
			Project project) {
		if (commitCache == null)
			commitCache = new HashMap<>();
		RevCommit commit;
		Optional<RevCommit> optional = commitCache.get(revId);
		if (optional == null) {
			try (RevWalk revWalk = new RevWalk(thisRepositoryManager.getRepository(project))) {
				optional = Optional.fromNullable(GitUtils.parseCommit(revWalk, revId));
			}
			commitCache.put(revId, optional);
		}
		commit = optional.orNull();
		if (mustExist && commit == null)
			throw new ObjectNotFoundException("Unable to find commit associated with object id: " + revId);
		else
			return commit;
	}

	public RevCommit getLastCommit(RepositoryManager thisRepositoryManager, Project project) {
		if (lastCommitHolder == null) {
			RevCommit lastCommit = null;
			try {
				for (Ref ref : thisRepositoryManager.getRepository(project).getRefDatabase()
						.getRefsByPrefix(Constants.R_HEADS)) {
					RevCommit commit = getRevCommit(ref.getObjectId(), false, thisRepositoryManager, project);
					if (commit != null) {
						if (lastCommit != null) {
							if (commit.getCommitTime() > lastCommit.getCommitTime())
								lastCommit = commit;
						} else {
							lastCommit = commit;
						}
					}
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			lastCommitHolder = Optional.fromNullable(lastCommit);
		}
		return lastCommitHolder.orNull();
	}

	@Nullable
	public Ref getRef(String revision, RepositoryManager thisRepositoryManager, Project project) {
		if (refCache == null)
			refCache = new HashMap<>();
		Optional<Ref> optional = refCache.get(revision);
		if (optional == null) {
			try {
				optional = Optional.fromNullable(thisRepositoryManager.getRepository(project).findRef(revision));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			refCache.put(revision, optional);
		}
		return optional.orNull();
	}

	@Nullable
	public String getDefaultBranch(RepositoryManager thisRepositoryManager, Project project) {
		return defaultBranchManager.getDefaultBranch(thisRepositoryManager, project);
	}

	public void setDefaultBranch(String defaultBranchName, RepositoryManager thisRepositoryManager, Project project) {
		defaultBranchManager.setDefaultBranch(defaultBranchName, thisRepositoryManager, project);
	}

	public List<RefInfo> getRefInfos(String prefix, RepositoryManager thisRepositoryManager, Project project) {
		try (RevWalk revWalk = new RevWalk(thisRepositoryManager.getRepository(project))) {
			List<Ref> refs = new ArrayList<Ref>(
					thisRepositoryManager.getRepository(project).getRefDatabase().getRefsByPrefix(prefix));
			List<RefInfo> refInfos = refs.stream().map(ref -> new RefInfo(revWalk, ref))
					.filter(refInfo -> refInfo.getPeeledObj() instanceof RevCommit).collect(Collectors.toList());
			Collections.sort(refInfos);
			Collections.reverse(refInfos);
			return refInfos;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void readObject(java.io.ObjectInputStream stream)
			throws java.io.IOException, java.lang.ClassNotFoundException {
		stream.defaultReadObject();
		this.defaultBranchManager = (DefaultBranchManager) stream.readObject();
	}

	private void writeObject(java.io.ObjectOutputStream stream) throws java.io.IOException {
		stream.defaultWriteObject();
		stream.writeObject(this.defaultBranchManager);
	}
}