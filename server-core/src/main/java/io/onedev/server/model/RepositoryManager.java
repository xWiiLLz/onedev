package io.onedev.server.model;


import org.eclipse.jgit.lib.Repository;
import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.ProjectManager;
import org.eclipse.jgit.api.Git;
import java.util.List;
import org.eclipse.jgit.lib.Ref;
import java.io.IOException;
import java.io.Serializable;

public class RepositoryManager implements Serializable {
	private transient Repository repository;

	public Repository getRepository(Project project) {
		if (repository == null)
			repository = OneDev.getInstance(ProjectManager.class).getRepository(project);
		return repository;
	}

	public Git git(Project project) {
		return Git.wrap(getRepository(project));
	}

	public List<Ref> getRefs(String prefix, Project project) {
		try {
			return getRepository(project).getRefDatabase().getRefsByPrefix(prefix);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}