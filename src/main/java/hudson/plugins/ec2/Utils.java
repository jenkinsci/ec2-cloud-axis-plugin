package hudson.plugins.ec2;

import java.io.IOException;
import java.util.List;

import org.jenkinsci.plugins.ec2axis.Ec2NodeAdderTask;

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
}
