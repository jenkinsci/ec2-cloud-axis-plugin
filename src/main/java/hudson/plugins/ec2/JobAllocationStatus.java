package hudson.plugins.ec2;

import hudson.model.Job;

public class JobAllocationStatus {
	public final Job<?,?> job;
	boolean allocated = false;
	public JobAllocationStatus(Job<?,?> job) {
		this.job = job;
	}
	
	public void setAllocated() {
		allocated = true;
	}
	
	public boolean isAllocated() {
		return allocated;
	}
}
