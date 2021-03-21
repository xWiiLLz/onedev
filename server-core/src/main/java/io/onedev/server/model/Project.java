package io.onedev.server.model;

import static io.onedev.server.model.Project.PROP_NAME;
import static io.onedev.server.model.Project.PROP_UPDATE_DATE;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.Table;

import org.apache.commons.lang3.SerializationUtils;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TagCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.LastCommitsOfChildren;
import org.eclipse.jgit.revwalk.LastCommitsOfChildren.Value;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.validator.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.onedev.commons.launcher.loader.ListenerRegistry;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.LinearRange;
import io.onedev.commons.utils.LockUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.server.OneDev;
import io.onedev.server.buildspec.BuildSpec;
import io.onedev.server.entitymanager.BuildManager;
import io.onedev.server.entitymanager.BuildQuerySettingManager;
import io.onedev.server.entitymanager.CodeCommentQuerySettingManager;
import io.onedev.server.entitymanager.CommitQuerySettingManager;
import io.onedev.server.entitymanager.IssueQuerySettingManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.entitymanager.PullRequestQuerySettingManager;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.entitymanager.UserManager;
import io.onedev.server.event.RefUpdated;
import io.onedev.server.git.BlameBlock;
import io.onedev.server.git.Blob;
import io.onedev.server.git.BlobIdent;
import io.onedev.server.git.BlobIdentFilter;
import io.onedev.server.git.GitUtils;
import io.onedev.server.git.RefInfo;
import io.onedev.server.git.Submodule;
import io.onedev.server.git.command.BlameCommand;
import io.onedev.server.git.command.ListChangedFilesCommand;
import io.onedev.server.git.exception.NotFileException;
import io.onedev.server.git.exception.ObjectNotFoundException;
import io.onedev.server.infomanager.CommitInfoManager;
import io.onedev.server.model.Build.Status;
import io.onedev.server.model.support.BranchProtection;
import io.onedev.server.model.support.FileProtection;
import io.onedev.server.model.support.NamedCodeCommentQuery;
import io.onedev.server.model.support.NamedCommitQuery;
import io.onedev.server.model.support.TagProtection;
import io.onedev.server.model.support.WebHook;
import io.onedev.server.model.support.build.ProjectBuildSetting;
import io.onedev.server.model.support.issue.ProjectIssueSetting;
import io.onedev.server.model.support.pullrequest.ProjectPullRequestSetting;
import io.onedev.server.persistence.SessionManager;
import io.onedev.server.persistence.TransactionManager;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.storage.StorageManager;
import io.onedev.server.util.CollectionUtils;
import io.onedev.server.util.ComponentContext;
import io.onedev.server.util.StatusInfo;
import io.onedev.server.util.diff.WhitespaceOption;
import io.onedev.server.util.jackson.DefaultView;
import io.onedev.server.util.match.Matcher;
import io.onedev.server.util.match.PathMatcher;
import io.onedev.server.util.patternset.PatternSet;
import io.onedev.server.util.reviewrequirement.ReviewRequirement;
import io.onedev.server.util.usermatch.UserMatch;
import io.onedev.server.util.validation.annotation.ProjectName;
import io.onedev.server.web.editable.annotation.Editable;
import io.onedev.server.web.editable.annotation.Markdown;
import io.onedev.server.web.util.ProjectAware;
import io.onedev.server.web.util.WicketUtils;

@Entity
@Table(indexes={@Index(columnList="o_forkedFrom_id"), @Index(columnList=PROP_NAME), 
		@Index(columnList=PROP_UPDATE_DATE)})
@Cache(usage=CacheConcurrencyStrategy.READ_WRITE)
//use dynamic update in order not to overwrite other edits while background threads change update date
@DynamicUpdate 
@Editable
public class Project extends AbstractEntity {

	private transient RefCommitManager commitManager = new RefCommitManager();

	private transient RepositoryManager repositoryManager = new RepositoryManager();

	private ForkManager forkManager = new ForkManager();

	private transient CommitStatusManager commitStatusManager = new CommitStatusManager();

	private TagBranchProtection tagBranchProtection = new TagBranchProtection();

	private MilestoneManager milestoneManager = new MilestoneManager();

	private static final long serialVersionUID = 1L;
	
	public static final String NAME_NAME = "Name";
	
	public static final String PROP_NAME = "name";
	
	public static final String NAME_UPDATE_DATE = "Update Date";
	
	public static final String PROP_UPDATE_DATE = "updateDate";
	
	public static final String NAME_DESCRIPTION = "Description";
	
	public static final String PROP_DESCRIPTION = "description";
	
	public static final String PROP_ID = "id";
	
	public static final String PROP_FORKED_FROM = "forkedFrom";
	
	public static final String PROP_USER_AUTHORIZATIONS = "userAuthorizations";
	
	public static final String PROP_GROUP_AUTHORIZATIONS = "groupAuthorizations";
	
	public static final List<String> QUERY_FIELDS = 
			Lists.newArrayList(NAME_NAME, NAME_DESCRIPTION, NAME_UPDATE_DATE);

	public static final Map<String, String> ORDER_FIELDS = CollectionUtils.newLinkedHashMap(
			NAME_NAME, PROP_NAME, 
			NAME_UPDATE_DATE, PROP_UPDATE_DATE);
	
	public static final int LAST_COMMITS_CACHE_THRESHOLD = 1000;
	
	public static final int MAX_UPLOAD_SIZE = 10; // In mega bytes
	
	static ThreadLocal<Stack<Project>> stack =  new ThreadLocal<Stack<Project>>() {

		@Override
		protected Stack<Project> initialValue() {
			return new Stack<Project>();
		}
	
	};
	
	public static void push(Project project) {
		stack.get().push(project);
	}

	public static void pop() {
		stack.get().pop();
	}

	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn
	private User owner;
	
	@Column(nullable=false, unique=true)
	private String name;
	
	@Column(length=15000)
	private String description;
	
    @OneToMany(mappedBy="project")
    private Collection<Build> builds = new ArrayList<>();
    
	@Column(nullable=false)
	private Date createDate = new Date();
	
	@Column(nullable=false)
	private Date updateDate = new Date();

	@OneToMany(mappedBy="targetProject", cascade=CascadeType.REMOVE)
	private Collection<PullRequest> incomingRequests = new ArrayList<>();
	
	@OneToMany(mappedBy="sourceProject")
	private Collection<PullRequest> outgoingRequests = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<Issue> issues = new ArrayList<>();
	
    @OneToMany(mappedBy="forkedFrom")
	@Cache(usage=CacheConcurrencyStrategy.READ_WRITE)
	private Collection<Project> forks = new ArrayList<>();
    
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	@Cache(usage=CacheConcurrencyStrategy.READ_WRITE)
	private Collection<GroupAuthorization> groupAuthorizations = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	@Cache(usage=CacheConcurrencyStrategy.READ_WRITE)
	private Collection<UserAuthorization> userAuthorizations = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<CodeComment> codeComments = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<IssueQuerySetting> userIssueQuerySettings = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<CommitQuerySetting> userCommitQuerySettings = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<PullRequestQuerySetting> userPullRequestQuerySettings = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<CodeCommentQuerySetting> userCodeCommentQuerySettings = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<BuildQuerySetting> userBuildQuerySettings = new ArrayList<>();
	
	private boolean issueManagementEnabled = true;
	
	@Lob
	@Column(length=65535, nullable=false)
	@JsonView(DefaultView.class)
	private ProjectIssueSetting issueSetting = new ProjectIssueSetting();
	
	@Lob
	@Column(length=65535, nullable=false)
	@JsonView(DefaultView.class)
	private ProjectBuildSetting buildSetting = new ProjectBuildSetting();
	
	@Lob
	@Column(length=65535, nullable=false)
	@JsonView(DefaultView.class)
	private ProjectPullRequestSetting pullRequestSetting = new ProjectPullRequestSetting();
	
	@Lob
	@Column(length=65535)
	@JsonView(DefaultView.class)
	private ArrayList<NamedCommitQuery> namedCommitQueries;
	
	@Lob
	@Column(length=65535)
	@JsonView(DefaultView.class)
	private ArrayList<NamedCodeCommentQuery> namedCodeCommentQueries;
	
	@Lob
	@Column(length=65535, nullable=false)
	@JsonView(DefaultView.class)
	private ArrayList<WebHook> webHooks = new ArrayList<>();
	
	private transient Map<BlobIdent, Optional<Blob>> blobCache;
    
    private transient Map<ObjectId, Optional<BuildSpec>> buildSpecCache;
    
    private transient Optional<IssueQuerySetting> issueQuerySettingOfCurrentUserHolder;
    
    private transient Optional<PullRequestQuerySetting> pullRequestQuerySettingOfCurrentUserHolder;
    
    private transient Optional<CodeCommentQuerySetting> codeCommentQuerySettingOfCurrentUserHolder;
    
    private transient Optional<BuildQuerySetting> buildQuerySettingOfCurrentUserHolder;
    
    private transient Optional<CommitQuerySetting> commitQuerySettingOfCurrentUserHolder;
    
    @Editable(order=100)
	@ProjectName
	@NotEmpty
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@Editable(order=200)
	@Markdown
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public ArrayList<BranchProtection> getBranchProtections() {
		return tagBranchProtection.getBranchProtections();
	}

	public void setBranchProtections(ArrayList<BranchProtection> branchProtections) {
		tagBranchProtection.setBranchProtections(branchProtections);
	}

	public ArrayList<TagProtection> getTagProtections() {
		return tagBranchProtection.getTagProtections();
	}

	public void setTagProtections(ArrayList<TagProtection> tagProtections) {
		tagBranchProtection.setTagProtections(tagProtections);
	}

	public Date getCreateDate() {
		return createDate;
	}

	public void setCreateDate(Date createDate) {
		this.createDate = createDate;
	}

	public Date getUpdateDate() {
		return updateDate;
	}

	public void setUpdateDate(Date updateDate) {
		this.updateDate = updateDate;
	}

	public Collection<PullRequest> getIncomingRequests() {
		return incomingRequests;
	}

	public void setIncomingRequests(Collection<PullRequest> incomingRequests) {
		this.incomingRequests = incomingRequests;
	}

	public Collection<PullRequest> getOutgoingRequests() {
		return outgoingRequests;
	}

	public void setOutgoingRequests(Collection<PullRequest> outgoingRequests) {
		this.outgoingRequests = outgoingRequests;
	}

	public Collection<GroupAuthorization> getGroupAuthorizations() {
		return groupAuthorizations;
	}

	public void setGroupAuthorizations(Collection<GroupAuthorization> groupAuthorizations) {
		this.groupAuthorizations = groupAuthorizations;
	}

	public Collection<UserAuthorization> getUserAuthorizations() {
		return userAuthorizations;
	}

	public void setUserAuthorizations(Collection<UserAuthorization> userAuthorizations) {
		this.userAuthorizations = userAuthorizations;
	}

	@Nullable
	public Project getForkedFrom() {
		return forkManager.getForkedFrom();
	}

	public void setForkedFrom(Project forkedFrom) {
		forkManager.setForkedFrom(forkedFrom);
	}

	public Collection<Project> getForks() {
		return forks;
	}

	public void setForks(Collection<Project> forks) {
		this.forks = forks;
	}
	
	public List<RefInfo> getBranchRefInfos() {
		List<RefInfo> refInfos = commitManager.getRefInfos(Constants.R_HEADS, this.repositoryManager, this);
		for (Iterator<RefInfo> it = refInfos.iterator(); it.hasNext();) {
			RefInfo refInfo = it.next();
			if (refInfo.getRef().getName().equals(GitUtils.branch2ref(commitManager.getDefaultBranch(this.repositoryManager, this)))) {
				it.remove();
				refInfos.add(0, refInfo);
				break;
			}
		}
		
		return refInfos;
    }
	
	public List<RefInfo> getTagRefInfos() {
		return commitManager.getRefInfos(Constants.R_TAGS, this.repositoryManager, this);
    }
	
	public List<RefInfo> getRefInfos(String prefix) {
		return commitManager.getRefInfos(prefix, this.repositoryManager, this);
    }

	public Git git() {
		return repositoryManager.git(this); 
	}
	
	public File getGitDir() {
		return OneDev.getInstance(StorageManager.class).getProjectGitDir(getId());
	}
	
	/**
	 * Find fork root of this project. 
	 * 
	 * @return
	 * 			fork root of this project
	 */
	public Project getForkRoot() {
		return forkManager.getForkRoot(this);
	}
	
	/**
	 * Get all descendant projects forking from current project.
	 * 
	 * @return
	 * 			all descendant projects forking from current project
	 */
	public List<Project> getForkChildren() {
		List<Project> children = new ArrayList<>();
		for (Project fork: getForks()) {  
			children.add(fork);
			children.addAll(fork.getForkChildren());
		}
		
		return children;
	}
	
	public List<Project> getForkParents() {
		return forkManager.getForkParents();
	}
	
	public Repository getRepository() {
		return repositoryManager.getRepository(this);
	}
	
	public String getUrl() {
		return OneDev.getInstance(SettingManager.class).getSystemSetting().getServerUrl() + "/projects/" + getName();
	}
	
	@Nullable
	public String getDefaultBranch() {
		return commitManager.getDefaultBranch(this.repositoryManager, this);
	}
	
	public void setDefaultBranch(String defaultBranchName) {
		commitManager.setDefaultBranch(defaultBranchName, this.repositoryManager, this);
	}
	
	private Map<BlobIdent, Optional<Blob>> getBlobCache() {
		if (blobCache == null) {
			synchronized(this) {
				if (blobCache == null)
					blobCache = new ConcurrentHashMap<>();
			}
		}
		return blobCache;
	}
	
	/**
	 * Read blob content and cache result in repository in case the same blob 
	 * content is requested again. 
	 * 
	 * We made this method thread-safe as we are using ForkJoinPool to calculate 
	 * diffs of multiple blob changes concurrently, and this method will be 
	 * accessed concurrently in that special case.
	 * 
	 * @param blobIdent
	 * 			ident of the blob
	 * @return
	 * 			blob of specified blob ident
	 * @throws
	 * 			ObjectNotFoundException if blob of specified ident can not be found in repository 
	 * 			
	 */
	@Nullable
	public Blob getBlob(BlobIdent blobIdent, boolean mustExist) {
		Preconditions.checkArgument(blobIdent.revision!=null && blobIdent.path!=null && blobIdent.mode!=null, 
				"Revision, path and mode of ident param should be specified");
		
		Optional<Blob> blob = getBlobCache().get(blobIdent);
		if (blob == null) {
			try (RevWalk revWalk = new RevWalk(repositoryManager.getRepository(this))) {
				ObjectId revId = commitManager.getObjectId(blobIdent.revision, mustExist, this.repositoryManager, this);		
				if (revId != null) {
					RevCommit commit = GitUtils.parseCommit(revWalk, revId);
					if (commit != null) {
						RevTree revTree = commit.getTree();
						TreeWalk treeWalk = TreeWalk.forPath(repositoryManager.getRepository(this), blobIdent.path, revTree);
						if (treeWalk != null) {
							ObjectId blobId = treeWalk.getObjectId(0);
							if (blobIdent.isGitLink()) {
								String url = getSubmodules(blobIdent.revision).get(blobIdent.path);
								if (url == null) {
									if (mustExist)
										throw new ObjectNotFoundException("Unable to find submodule '" + blobIdent.path + "' in .gitmodules");
									else
										blob = Optional.absent();
								} else {
									String hash = blobId.name();
									blob = Optional.of(new Blob(blobIdent, blobId, new Submodule(url, hash).toString().getBytes()));
								}
							} else if (blobIdent.isTree()) {
								throw new NotFileException("Path '" + blobIdent.path + "' is a tree");
							} else {
								blob = Optional.of(new Blob(blobIdent, blobId, treeWalk.getObjectReader()));
							}
						} 
					} 				
				} 
				if (blob == null) {
					if (mustExist)
						throw new ObjectNotFoundException("Unable to find blob ident: " + blobIdent);
					else 
						blob = Optional.absent();
				}
				getBlobCache().put(blobIdent, blob);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return blob.orNull();
	}
	
	public InputStream getInputStream(BlobIdent ident) {
		try (RevWalk revWalk = new RevWalk(repositoryManager.getRepository(this))) {
			ObjectId commitId = commitManager.getObjectId(ident.revision, true, this.repositoryManager, this);
			RevTree revTree = revWalk.parseCommit(commitId).getTree();
			TreeWalk treeWalk = TreeWalk.forPath(repositoryManager.getRepository(this), ident.path, revTree);
			if (treeWalk != null) {
				ObjectLoader objectLoader = treeWalk.getObjectReader().open(treeWalk.getObjectId(0));
				return objectLoader.openStream();
			} else {
				throw new ObjectNotFoundException("Unable to find blob path '" + ident.path + "' in revision '" + ident.revision + "'");
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Get cached object id of specified revision.
	 * 
	 * @param revision
	 * 			revision to resolve object id for
	 * @param mustExist
	 * 			true to have the method throwing exception instead 
	 * 			of returning null if the revision does not exist
	 * @return
	 * 			object id of specified revision, or <tt>null</tt> if revision 
	 * 			does not exist and mustExist is specified as false
	 */
	@Nullable
	public ObjectId getObjectId(String revision, boolean mustExist) {
		return commitManager.getObjectId(revision, mustExist, this.repositoryManager, this);
	}
	
	public void cacheObjectId(String revision, @Nullable ObjectId objectId) {
		commitManager.cacheObjectId(revision, objectId);
	}

	public Map<String, Status> getCommitStatus(ObjectId commitId, 
			@Nullable PullRequest request, @Nullable String refName) {
		return commitStatusManager.getCommitStatus(commitId, request, refName, this);
	}
	
	public void cacheCommitStatus(Map<ObjectId, Map<String, Collection<StatusInfo>>> commitStatuses) {
		commitStatusManager.cacheCommitStatus(commitStatuses);
	}
	
	/**
	 * Get build spec of specified commit
	 * @param commitId
	 * 			commit id to get build spec for 
	 * @return
	 * 			build spec of specified commit, or <tt>null</tt> if no build spec is defined and 
	 * 			auto-detection also can not provide an appropriate build spec  
	 * @throws
	 * 			Exception when build spec is defined but not valid
	 */
	@Nullable
	public BuildSpec getBuildSpec(ObjectId commitId) {
		if (buildSpecCache == null)
			buildSpecCache = new HashMap<>();
		Optional<BuildSpec> buildSpec = buildSpecCache.get(commitId);
		if (buildSpec == null) {
			Blob blob = getBlob(new BlobIdent(commitId.name(), BuildSpec.BLOB_PATH, FileMode.TYPE_FILE), false);
			if (blob != null) {  
				buildSpec = Optional.fromNullable(BuildSpec.parse(blob.getBytes()));
			} else { 
				Blob oldBlob = getBlob(new BlobIdent(commitId.name(), ".onedev-buildspec", FileMode.TYPE_FILE), false);
				if (oldBlob != null)
					buildSpec = Optional.fromNullable(BuildSpec.parse(oldBlob.getBytes()));
				else
					buildSpec = Optional.absent();
			}
			buildSpecCache.put(commitId, buildSpec);
		}
		return buildSpec.orNull();
	}
	
	public List<String> getJobNames() {
		List<String> jobNames = new ArrayList<>();
		if (commitManager.getDefaultBranch(this.repositoryManager, this) != null) {
			BuildSpec buildSpec = getBuildSpec(commitManager.getObjectId(commitManager.getDefaultBranch(this.repositoryManager, this), true, this.repositoryManager, this));
			if (buildSpec != null)
				jobNames.addAll(buildSpec.getJobMap().keySet());
		}
		return jobNames;
	}
	
	public RevCommit getLastCommit() {
		return commitManager.getLastCommit(this.repositoryManager, this);
	}
	
	public LastCommitsOfChildren getLastCommitsOfChildren(String revision, @Nullable String path) {
		return commitManager.getLastCommitsOfChildren(revision, path, this.repositoryManager, this);
	}

	@Nullable
	public Ref getRef(String revision) {
		return commitManager.getRef(revision, this.repositoryManager, this);
	}
	
	@Nullable
	public String getRefName(String revision) {
		Ref ref = commitManager.getRef(revision, this.repositoryManager, this);
		return ref != null? ref.getName(): null;
	}
	
	@Nullable
	public Ref getBranchRef(String revision) {
		Ref ref = commitManager.getRef(revision, this.repositoryManager, this);
		if (ref != null && ref.getName().startsWith(Constants.R_HEADS))
			return ref;
		else
			return null;
	}
	
	@Nullable
	public Ref getTagRef(String revision) {
		Ref ref = commitManager.getRef(revision, this.repositoryManager, this);
		if (ref != null && ref.getName().startsWith(Constants.R_TAGS))
			return ref;
		else
			return null;
	}
	
	@Nullable
	public RevCommit getRevCommit(String revision, boolean mustExist) {
		ObjectId revId = commitManager.getObjectId(revision, mustExist, this.repositoryManager, this);
		if (revId != null) {
			return commitManager.getRevCommit(revId, mustExist, this.repositoryManager, this);
		} else {
			return null;
		}
	}
	
	@Nullable
	public RevCommit getRevCommit(ObjectId revId, boolean mustExist) {
		return commitManager.getRevCommit(revId, mustExist, this.repositoryManager, this);
	}
	
	public List<Ref> getRefs(String prefix) {
		return repositoryManager.getRefs(prefix, this); 
	}
	
	public Map<String, String> getSubmodules(String revision) {
		Map<String, String> submodules = new HashMap<>();
		
		Blob blob = getBlob(new BlobIdent(revision, ".gitmodules", FileMode.REGULAR_FILE.getBits()), true);
		String content = new String(blob.getBytes());
		
		String path = null;
		String url = null;
		
		for (String line: StringUtils.splitAndTrim(content, "\r\n")) {
			if (line.startsWith("[") && line.endsWith("]")) {
				if (path != null && url != null)
					submodules.put(path, url);
				
				path = url = null;
			} else if (line.startsWith("path")) {
				path = StringUtils.substringAfter(line, "=").trim();
			} else if (line.startsWith("url")) {
				url = StringUtils.substringAfter(line, "=").trim();
			}
		}
		if (path != null && url != null)
			submodules.put(path, url);
		
		return submodules;
	}
    
    public void createBranch(String branchName, String branchRevision) {
		try {
			CreateBranchCommand command = repositoryManager.git(this).branchCreate();
			command.setName(branchName);
			RevCommit commit = getRevCommit(branchRevision, true);
			command.setStartPoint(getRevCommit(branchRevision, true));
			command.call();
			String refName = GitUtils.branch2ref(branchName); 
			commitManager.cacheObjectId(refName, commit);
			
	    	ObjectId commitId = commit.copy();
	    	OneDev.getInstance(TransactionManager.class).runAfterCommit(new Runnable() {

				@Override
				public void run() {
			    	OneDev.getInstance(SessionManager.class).runAsync(new Runnable() {

						@Override
						public void run() {
							Project project = OneDev.getInstance(ProjectManager.class).load(getId());
							OneDev.getInstance(ListenerRegistry.class).post(
									new RefUpdated(project, refName, ObjectId.zeroId(), commitId));
						}
			    		
			    	});
				}
	    		
	    	});			
		} catch (GitAPIException e) {
			throw new RuntimeException(e);
		}
    }
    
    public void createTag(String tagName, String tagRevision, PersonIdent taggerIdent, @Nullable String tagMessage) {
		try {
			TagCommand tag = repositoryManager.git(this).tag();
			tag.setName(tagName);
			if (tagMessage != null)
				tag.setMessage(tagMessage);
			tag.setTagger(taggerIdent);
			tag.setObjectId(getRevCommit(tagRevision, true));
			tag.call();
			
			String refName = GitUtils.tag2ref(tagName);
			commitManager.cacheObjectId(refName, tag.getObjectId());
			
	    	ObjectId commitId = tag.getObjectId().copy();
	    	OneDev.getInstance(TransactionManager.class).runAfterCommit(new Runnable() {

				@Override
				public void run() {
			    	OneDev.getInstance(SessionManager.class).runAsync(new Runnable() {

						@Override
						public void run() {
							Project project = OneDev.getInstance(ProjectManager.class).load(getId());
							OneDev.getInstance(ListenerRegistry.class).post(
									new RefUpdated(project, refName, ObjectId.zeroId(), commitId));
						}
			    		
			    	});
				}
	    		
	    	});			
		} catch (GitAPIException e) {
			throw new RuntimeException(e);
		}
    }
    
	public Collection<CodeComment> getCodeComments() {
		return codeComments;
	}

	public void setCodeComments(Collection<CodeComment> codeComments) {
		this.codeComments = codeComments;
	}
	
	@Editable(order=300, name="Issue management", description="Whether or not to provide issue management for the project")
	public boolean isIssueManagementEnabled() {
		return issueManagementEnabled;
	}
	
	public void setIssueManagementEnabled(boolean issueManagementEnabled) {
		this.issueManagementEnabled = issueManagementEnabled;
	}
	
	public ProjectIssueSetting getIssueSetting() {
		return issueSetting;
	}

	public void setIssueSetting(ProjectIssueSetting issueSetting) {
		this.issueSetting = issueSetting;
	}

	public ProjectBuildSetting getBuildSetting() {
		return buildSetting;
	}

	public void setBuildSetting(ProjectBuildSetting buildSetting) {
		this.buildSetting = buildSetting;
	}

	public ProjectPullRequestSetting getPullRequestSetting() {
		return pullRequestSetting;
	}

	public void setPullRequestSetting(ProjectPullRequestSetting pullRequestSetting) {
		this.pullRequestSetting = pullRequestSetting;
	}
	
	public ArrayList<NamedCommitQuery> getNamedCommitQueries() {
		if (namedCommitQueries == null) {
			namedCommitQueries = new ArrayList<>();
			namedCommitQueries.add(new NamedCommitQuery("All", null));
			namedCommitQueries.add(new NamedCommitQuery("Default branch", "default-branch"));
			namedCommitQueries.add(new NamedCommitQuery("Authored by me", "authored-by-me"));
			namedCommitQueries.add(new NamedCommitQuery("Committed by me", "committed-by-me"));
			namedCommitQueries.add(new NamedCommitQuery("Committed recently", "after(last week)"));
		}
		return namedCommitQueries;
	}

	public void setNamedCommitQueries(ArrayList<NamedCommitQuery> namedCommitQueries) {
		this.namedCommitQueries = namedCommitQueries;
	}

	public ArrayList<NamedCodeCommentQuery> getNamedCodeCommentQueries() {
		if (namedCodeCommentQueries == null) {
			namedCodeCommentQueries = new ArrayList<>(); 
			namedCodeCommentQueries.add(new NamedCodeCommentQuery("All", null));
			namedCodeCommentQueries.add(new NamedCodeCommentQuery("Created by me", "created by me"));
			namedCodeCommentQueries.add(new NamedCodeCommentQuery("Created recently", "\"Create Date\" is since \"last week\""));
			namedCodeCommentQueries.add(new NamedCodeCommentQuery("Updated recently", "\"Update Date\" is since \"last week\""));
		}
		return namedCodeCommentQueries;
	}

	public void setNamedCodeCommentQueries(ArrayList<NamedCodeCommentQuery> namedCodeCommentQueries) {
		this.namedCodeCommentQueries = namedCodeCommentQueries;
	}
	
	public Collection<IssueQuerySetting> getUserIssueQuerySettings() {
		return userIssueQuerySettings;
	}

	public void setUserIssueQuerySettings(Collection<IssueQuerySetting> userIssueQuerySettings) {
		this.userIssueQuerySettings = userIssueQuerySettings;
	}

	public Collection<CommitQuerySetting> getUserCommitQuerySettings() {
		return userCommitQuerySettings;
	}

	public void setUserCommitQuerySettings(Collection<CommitQuerySetting> userCommitQuerySettings) {
		this.userCommitQuerySettings = userCommitQuerySettings;
	}

	public Collection<PullRequestQuerySetting> getUserPullRequestQuerySettings() {
		return userPullRequestQuerySettings;
	}

	public void setUserPullRequestQuerySettings(Collection<PullRequestQuerySetting> userPullRequestQuerySettings) {
		this.userPullRequestQuerySettings = userPullRequestQuerySettings;
	}

	public Collection<CodeCommentQuerySetting> getUserCodeCommentQuerySettings() {
		return userCodeCommentQuerySettings;
	}

	public void setUserCodeCommentQuerySettings(Collection<CodeCommentQuerySetting> userCodeCommentQuerySettings) {
		this.userCodeCommentQuerySettings = userCodeCommentQuerySettings;
	}
	
	public Collection<BuildQuerySetting> getUserBuildQuerySettings() {
		return userBuildQuerySettings;
	}

	public void setUserBuildQuerySettings(Collection<BuildQuerySetting> userBuildQuerySettings) {
		this.userBuildQuerySettings = userBuildQuerySettings;
	}

	public Collection<Build> getBuilds() {
		return builds;
	}

	public void setBuilds(Collection<Build> builds) {
		this.builds = builds;
	}

	public List<BlobIdent> getChildren(BlobIdent blobIdent, BlobIdentFilter blobIdentFilter) {
		return getChildren(blobIdent, blobIdentFilter, commitManager.getObjectId(blobIdent.revision, true, this.repositoryManager, this));
	}
	
	public List<BlobIdent> getChildren(BlobIdent blobIdent, BlobIdentFilter blobIdentFilter, ObjectId commitId) {
		Repository repository = repositoryManager.getRepository(this);
		try (RevWalk revWalk = new RevWalk(repository)) {
			RevTree revTree = revWalk.parseCommit(commitId).getTree();
			
			TreeWalk treeWalk;
			if (blobIdent.path != null) {
				treeWalk = TreeWalk.forPath(repository, blobIdent.path, revTree);
				treeWalk.enterSubtree();
			} else {
				treeWalk = new TreeWalk(repository);
				treeWalk.addTree(revTree);
			}
			
			List<BlobIdent> children = new ArrayList<>();
			while (treeWalk.next()) { 
				BlobIdent child = new BlobIdent(blobIdent.revision, treeWalk.getPathString(), treeWalk.getRawMode(0)); 
				if (blobIdentFilter.filter(child))
					children.add(child);
			}
			Collections.sort(children);
			return children;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public int getMode(String revision, @Nullable String path) {
		if (path != null) {
			RevCommit commit = getRevCommit(revision, true);
			try {
				TreeWalk treeWalk = TreeWalk.forPath(repositoryManager.getRepository(this), path, commit.getTree());
				if (treeWalk != null) {
					return treeWalk.getRawMode(0);
				} else {
					throw new ObjectNotFoundException("Unable to find blob path '" + path
							+ "' in revision '" + revision + "'");
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			return FileMode.TREE.getBits();
		}
	}

	public Collection<Milestone> getMilestones() {
		return milestoneManager.getMilestones();
	}

	public void setMilestones(Collection<Milestone> milestones) {
		milestoneManager.setMilestones(milestones);
	}

	public List<Milestone> getSortedMilestones() {
		return milestoneManager.getSortedMilestones();
	}
	
	@Editable
	public ArrayList<WebHook> getWebHooks() {
		return webHooks;
	}

	public void setWebHooks(ArrayList<WebHook> webHooks) {
		this.webHooks = webHooks;
	}

	public TagProtection getTagProtection(String tagName, User user) {
		return tagBranchProtection.getTagProtection(tagName, user, this);
	}
	
	public BranchProtection getBranchProtection(String branchName, @Nullable User user) {
		return tagBranchProtection.getBranchProtection(branchName, user, this);
	}

	@Override
	public String toString() {
		return getName();
	}

	public List<User> getAuthors(String filePath, ObjectId commitId, @Nullable LinearRange range) {
		BlameCommand cmd = new BlameCommand(getGitDir());
		cmd.commitHash(commitId.name());
		cmd.file(filePath);
		cmd.range(range);

		List<User> authors = new ArrayList<>();
		UserManager userManager = OneDev.getInstance(UserManager.class);
		for (BlameBlock block: cmd.call()) {
			User author = userManager.find(block.getCommit().getAuthor());
			if (author != null && !authors.contains(author))
				authors.add(author);
		}
		
		return authors;
	}
	
	public IssueQuerySetting getIssueQuerySettingOfCurrentUser() {
		if (issueQuerySettingOfCurrentUserHolder == null) {
			User user = SecurityUtils.getUser();
			if (user != null) {
				IssueQuerySetting setting = OneDev.getInstance(IssueQuerySettingManager.class).find(this, user);
				if (setting == null) {
					setting = new IssueQuerySetting();
					setting.setProject(this);
					setting.setUser(user);
				}
				issueQuerySettingOfCurrentUserHolder = Optional.of(setting);
			} else {
				issueQuerySettingOfCurrentUserHolder = Optional.absent();
			}
		}
		return issueQuerySettingOfCurrentUserHolder.orNull();
	}
	
	public CommitQuerySetting getCommitQuerySettingOfCurrentUser() {
		if (commitQuerySettingOfCurrentUserHolder == null) {
			User user = SecurityUtils.getUser();
			if (user != null) {
				CommitQuerySetting setting = OneDev.getInstance(CommitQuerySettingManager.class).find(this, user);
				if (setting == null) {
					setting = new CommitQuerySetting();
					setting.setProject(this);
					setting.setUser(user);
				}
				commitQuerySettingOfCurrentUserHolder = Optional.of(setting);
			} else {
				commitQuerySettingOfCurrentUserHolder = Optional.absent();
			}
		}
		return commitQuerySettingOfCurrentUserHolder.orNull();
	}
	
	@Nullable
	public PullRequestQuerySetting getPullRequestQuerySettingOfCurrentUser() {
		if (pullRequestQuerySettingOfCurrentUserHolder == null) {
			User user = SecurityUtils.getUser();
			if (user != null) {
				PullRequestQuerySetting setting = OneDev.getInstance(PullRequestQuerySettingManager.class).find(this, user);
				if (setting == null) {
					setting = new PullRequestQuerySetting();
					setting.setProject(this);
					setting.setUser(user);
				}
				pullRequestQuerySettingOfCurrentUserHolder = Optional.of(setting);
			} else {
				pullRequestQuerySettingOfCurrentUserHolder = Optional.absent();
			}
		}
		return pullRequestQuerySettingOfCurrentUserHolder.orNull();
	}
	
	@Nullable
	public CodeCommentQuerySetting getCodeCommentQuerySettingOfCurrentUser() {
		if (codeCommentQuerySettingOfCurrentUserHolder == null) {
			User user = SecurityUtils.getUser();
			if (user != null) {
				CodeCommentQuerySetting setting = OneDev.getInstance(CodeCommentQuerySettingManager.class).find(this, user);
				if (setting == null) {
					setting = new CodeCommentQuerySetting();
					setting.setProject(this);
					setting.setUser(user);
				}
				codeCommentQuerySettingOfCurrentUserHolder = Optional.of(setting);
			} else {
				codeCommentQuerySettingOfCurrentUserHolder = Optional.absent();
			}
		}
		return codeCommentQuerySettingOfCurrentUserHolder.orNull();
	}
	
	@Nullable
	public BuildQuerySetting getBuildQuerySettingOfCurrentUser() {
		if (buildQuerySettingOfCurrentUserHolder == null) {
			User user = SecurityUtils.getUser();
			if (user != null) {
				BuildQuerySetting setting = OneDev.getInstance(BuildQuerySettingManager.class).find(this, user);
				if (setting == null) {
					setting = new BuildQuerySetting();
					setting.setProject(this);
					setting.setUser(user);
				}
				buildQuerySettingOfCurrentUserHolder = Optional.of(setting);
			} else {
				buildQuerySettingOfCurrentUserHolder = Optional.absent();
			}
		}
		return buildQuerySettingOfCurrentUserHolder.orNull();
	}
	
	@Nullable
	public Milestone getMilestone(@Nullable String milestoneName) {
		return milestoneManager.getMilestone(milestoneName);
	}

	public boolean isCommitOnBranches(@Nullable ObjectId commitId, String branches) {
		Matcher matcher = new PathMatcher();
		if (commitId != null) {
			CommitInfoManager commitInfoManager = OneDev.getInstance(CommitInfoManager.class);
			Collection<ObjectId> descendants = commitInfoManager.getDescendants(this, Sets.newHashSet(commitId));
			descendants.add(commitId);
		
			PatternSet branchPatterns = PatternSet.parse(branches);
			for (RefInfo ref: getBranchRefInfos()) {
				String branchName = Preconditions.checkNotNull(GitUtils.ref2branch(ref.getRef().getName()));
				if (descendants.contains(ref.getPeeledObj()) && branchPatterns.matches(matcher, branchName))
					return true;
			}
			return false;
		} else {
			return PatternSet.parse(branches).matches(matcher, "master");
		}
	}
	
	public Collection<String> getChangedFiles(ObjectId oldObjectId, ObjectId newObjectId, 
			Map<String, String> gitEnvs) {
		if (gitEnvs != null && !gitEnvs.isEmpty()) {
			ListChangedFilesCommand cmd = new ListChangedFilesCommand(getGitDir(), gitEnvs);
			cmd.fromRev(oldObjectId.name()).toRev(newObjectId.name());
			return cmd.call();
		} else {
			return GitUtils.getChangedFiles(repositoryManager.getRepository(this), oldObjectId, newObjectId);
		}
	}
	
	public boolean isReviewRequiredForModification(User user, String branch, @Nullable String file) {
		return tagBranchProtection.isReviewRequiredForModification(user, branch, file, this);
	}

	public boolean isReviewRequiredForPush(User user, String branch, ObjectId oldObjectId, 
			ObjectId newObjectId, Map<String, String> gitEnvs) {
		return tagBranchProtection.isReviewRequiredForPush(user, branch, oldObjectId, newObjectId, gitEnvs, this);
	}
	
	public boolean isBuildRequiredForModification(User user, String branch, @Nullable String file) {
		return tagBranchProtection.isBuildRequiredForModification(user, branch, file, this);
	}
	
	public boolean isBuildRequiredForPush(User user, String branch, ObjectId oldObjectId, ObjectId newObjectId, 
			Map<String, String> gitEnvs) {
		return tagBranchProtection.isBuildRequiredForPush(user, branch, oldObjectId, newObjectId, gitEnvs, this);
	}
	
	@Nullable
	public List<String> readLines(BlobIdent blobIdent, WhitespaceOption whitespaceOption, boolean mustExist) {
		Blob blob = getBlob(blobIdent, mustExist);
		if (blob != null) {
			Blob.Text text = blob.getText();
			if (text != null) {
				List<String> normalizedLines = new ArrayList<>();
				for (String line: text.getLines()) 
					normalizedLines.add(whitespaceOption.process(line));
				return normalizedLines;
			}
		}
		return null;
	}
	
	@Nullable
	public static Project get() {
		if (!stack.get().isEmpty()) { 
			return stack.get().peek();
		} else {
			ComponentContext componentContext = ComponentContext.get();
			if (componentContext != null) {
				ProjectAware projectAware = WicketUtils.findInnermost(componentContext.getComponent(), ProjectAware.class);
				if (projectAware != null) 
					return projectAware.getProject();
			}
			return null;
		}
	}

	private void readObject(java.io.ObjectInputStream stream)
			throws java.io.IOException, java.lang.ClassNotFoundException {
		stream.defaultReadObject();
		this.commitManager = (RefCommitManager) stream.readObject();
		this.repositoryManager = (RepositoryManager) stream.readObject();
		this.commitStatusManager = (CommitStatusManager) stream.readObject();
	}

	private void writeObject(java.io.ObjectOutputStream stream) throws java.io.IOException {
		stream.defaultWriteObject();
		stream.writeObject(this.commitManager);
		stream.writeObject(this.repositoryManager);
		stream.writeObject(this.commitStatusManager);
	}
	
}
