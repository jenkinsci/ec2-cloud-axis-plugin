package hudson.plugins.ec2;

import java.io.PrintStream;
import java.util.Collection;
import java.util.concurrent.ExecutionException;

import hudson.matrix.MatrixProject;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Queue.Item;
import hudson.model.Queue.Task;
import hudson.slaves.NodeProvisioner.PlannedNode;
import jenkins.model.Jenkins;

public class JobAllocationManager {
	public final Job<?,?> job;
	boolean allocated = false;
	private final Integer instanceBootTimeoutLimit;
	private Label buildLabel;
	private final long startedTime;
	private final int matrixId;
	private PrintStream buildLogger;
	private Collection<PlannedNode> plannedNodes;
	
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

	public void handleAllocationReattempt() {
		if (launchErrorsOccurred()) {
			cancelAllItemsForGivenLabel("Launch errors.");
			return;
		}
		
		if (isBuildExpired()) {
			cancelAllItemsForGivenLabel("The slaves didn't come up after " + getStartupElapsedTime() + " ms.");
			return;
		}
		for (PlannedNode plannedNode : plannedNodes) {
			buildLogger.println("Label " + buildLabel.getDisplayName() + " has planned node " + plannedNode.displayName);
		}
	}

	private boolean isBuildExpired() {
		return getStartupElapsedTime() >= instanceBootTimeoutLimit;
	}

	private long getStartupElapsedTime() {
		long currentTimeMillis = System.currentTimeMillis();
		return currentTimeMillis - startedTime;
	}

	private boolean launchErrorsOccurred() {
		boolean errorOccurred = false;
		for (PlannedNode plannedNode : plannedNodes) {
			if (plannedNode.future.isDone())
				errorOccurred = isFailedProvision(plannedNode);
		}
		return errorOccurred;
	}

	private boolean isFailedProvision(PlannedNode plannedNode) {
		final String displayName = buildLabel.getDisplayName();
		try {
			plannedNode.future.get();
		} catch (InterruptedException e) {
			buildLogger.println("Launch of slave for node " + displayName + " was interrupted");
			e.printStackTrace(buildLogger);
			return true;
		} catch (ExecutionException e) {
			buildLogger.println("An exception occurred while trying to provision node: " + displayName);
			e.printStackTrace(buildLogger);
			return true;
		}
		return false;
	}

	public void info(String message) {
		buildLogger.println(message);
	}

	public void setPlannedNodes(Collection<PlannedNode> plannedNodes) {
		this.plannedNodes = plannedNodes;
	}

	private void cancelAllItemsForGivenLabel(String reason) {
		buildLogger.println("Will cancel construction with label " +
				buildLabel.getDisplayName() + ". Reason: " + reason);
		
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
