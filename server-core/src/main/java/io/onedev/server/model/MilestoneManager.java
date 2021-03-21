package io.onedev.server.model;


import javax.persistence.OneToMany;
import javax.persistence.CascadeType;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import java.util.stream.Collectors;
import java.util.Collections;
import java.io.Serializable;

public class MilestoneManager implements Serializable {
	private Collection<Milestone> milestones = new ArrayList<>();
	private transient List<Milestone> sortedMilestones;

	public Collection<Milestone> getMilestones() {
		return milestones;
	}

	public void setMilestones(Collection<Milestone> milestones) {
		this.milestones = milestones;
	}

	@Nullable
	public Milestone getMilestone(@Nullable String milestoneName) {
		for (Milestone milestone : milestones) {
			if (milestone.getName().equals(milestoneName))
				return milestone;
		}
		return null;
	}

	public List<Milestone> getSortedMilestones() {
		if (sortedMilestones == null) {
			sortedMilestones = new ArrayList<>();
			List<Milestone> open = milestones.stream().filter(it -> !it.isClosed())
					.sorted(new Milestone.DueDateComparator()).collect(Collectors.toList());
			sortedMilestones.addAll(open);
			List<Milestone> closed = milestones.stream().filter(it -> it.isClosed())
					.sorted(new Milestone.DueDateComparator()).collect(Collectors.toList());
			Collections.reverse(closed);
			sortedMilestones.addAll(closed);
		}
		return sortedMilestones;
	}
}