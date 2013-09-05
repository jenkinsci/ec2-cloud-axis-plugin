package hudson.plugins.ec2;

import java.io.PrintStream;

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
	private final int matrixId;
	private PrintStream buildLogger;
	
	public JobAllocationManager(
			MatrixProject matrixProject, 
			PrintStream buildLogger, 
			Label buildLabel, 
			Integer instanceBootTimeoutLimit, 
			int matrixId) 
	{
		this.job = matrixProject;
		this.buildLogger = buildLogger;
		this.buildLabel = buildLabel;
		this.instanceBootTimeoutLimit = instanceBootTimeoutLimit;
		this.matrixId = matrixId;
		startedTime = System.currentTimeMillis();	
	}
	
	public void setAllocated() {
		allocated = true;
	}
	
	public boolean isAllocated() {
		return allocated;
	}
	
	public int getMatrixId() {
		return matrixId;
	}

	public void abortBuildIfBootTimedOut() {
		long currentTimeMillis = System.currentTimeMillis();
		long elapsedTime = currentTimeMillis - startedTime;
		
		if (elapsedTime < instanceBootTimeoutLimit)
			return;
		
		buildLogger.println("Build " +
				buildLabel.getDisplayName() + 
				" didn't come up after " + elapsedTime + " ms");
		
		cancelAllItemsForGivenLabel();
	}

	private void cancelAllItemsForGivenLabel() {
		buildLogger.println("Will cancel construction with label " +
				buildLabel.getDisplayName());
		
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
