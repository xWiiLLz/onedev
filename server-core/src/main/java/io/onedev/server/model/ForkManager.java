package io.onedev.server.model;


import javax.persistence.ManyToOne;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import java.util.List;
import java.util.ArrayList;
import java.io.Serializable;

public class ForkManager implements Serializable {
	private Project forkedFrom;

	public Project getForkedFrom() {
		return forkedFrom;
	}

	public void setForkedFrom(Project forkedFrom) {
		this.forkedFrom = forkedFrom;
	}

	/**
	* Find fork root of this project. 
	* @return fork root of this project
	*/
	public Project getForkRoot(Project project) {
		if (forkedFrom != null)
			return forkedFrom.getForkRoot();
		else
			return project;
	}

	public List<Project> getForkParents() {
		List<Project> forkParents = new ArrayList<>();
		if (forkedFrom != null) {
			forkParents.add(forkedFrom);
			forkParents.addAll(forkedFrom.getForkParents());
		}
		return forkParents;
	}
}