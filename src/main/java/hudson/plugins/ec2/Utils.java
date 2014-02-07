package hudson.plugins.ec2;

import hudson.model.Queue;

import java.io.IOException;
import java.util.List;

import jenkins.model.Jenkins;

import org.jenkinsci.plugins.ec2axis.Ec2SafeNodeTaskWorker;


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

	public static void addNodesAndWait(final List<EC2AbstractSlave> allocatedSlaves) {
		Ec2SafeNodeTaskWorker.invokeAndWait(new Runnable() {
			@Override public void run() { for (EC2AbstractSlave nodeToAdd : allocatedSlaves) {
					try {
						Jenkins.getInstance().addNode(nodeToAdd);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
			}
		});
	}
}
