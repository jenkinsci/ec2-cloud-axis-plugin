package hudson.plugins.ec2;

import java.io.IOException;

import hudson.model.Hudson;
import hudson.model.Queue;
import jenkins.model.Jenkins;


public class Utils {
	public static void finishSlaveAndQueuedItems(EC2AbstractSlave slave) {
		Queue.Item[] items = Jenkins.getInstance().getQueue().getItems();
		for (Queue.Item item : items) {
			if (item.task.getAssignedLabel().getDisplayName().equals(slave.getDisplayName())) {
				Jenkins.getInstance().getQueue().cancel(item);
			}
		}
		if (!slave.stopOnTerminate)
			slave.terminate();
	}

	public static void addNode(EC2AbstractSlave ec2Slave) throws IOException {
		/* 
		  We need to get the Ec2RetentionStrategy lock because of a deadlock condition:
		  the Ec2RetentionStrategy.check might trigger a delete node that needs to lock the list of nodes.
		  
		  The deadlock occurs because of Jenkins Cron Thread that might trigger the Ec2RetentionStrategy.check.
		*/ 
		synchronized (ec2Slave.getRetentionStrategy()) {
			Hudson.getInstance().addNode(ec2Slave);
		}
	}
}
