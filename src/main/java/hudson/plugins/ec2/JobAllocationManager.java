package hudson.plugins.ec2;

import hudson.matrix.MatrixProject;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Queue.Item;
import hudson.model.Queue.Task;
import jenkins.model.Jenkins;

public class JobAllocationManager {
	public final Job<?,?> job;
	boolean allocated = false;
	private final Integer instanceBootTimeoutLimit;
	private Label buildLabel;
	private final long startedTime;
	
	public JobAllocationManager(MatrixProject matrixProject, Label buildLabel, Integer instanceBootTimeoutLimit) {
		this.job = matrixProject;
		this.buildLabel = buildLabel;
		this.instanceBootTimeoutLimit = instanceBootTimeoutLimit;
		startedTime = System.currentTimeMillis();		
	}
	
	public void setAllocated() {
		allocated = true;
	}
	
	public boolean isAllocated() {
		return allocated;
	}

	public void abortBuildIfBootTimedOut() {
		long currentTimeMillis = System.currentTimeMillis();
		long elapsedTime = currentTimeMillis - startedTime;
		
		if (elapsedTime < instanceBootTimeoutLimit)
			return;
		
		cancelAllItemsForGivenLabel();
	}

	private void cancelAllItemsForGivenLabel() {
		Queue queue = Jenkins.getInstance().getQueue();
		Item[] items = queue.getItems();
		for (Item item : items) {
			Task task = item.task;
			if (task.getAssignedLabel().getName().equals(buildLabel.getDisplayName())) {
				if (job.getAllJobs().contains(task)) {
					queue.cancel(item);
				}
			}
		}
	}
}
