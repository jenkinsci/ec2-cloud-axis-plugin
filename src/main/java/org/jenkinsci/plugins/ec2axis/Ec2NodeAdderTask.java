package org.jenkinsci.plugins.ec2axis;

import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import jenkins.model.Jenkins;
import hudson.Extension;
import hudson.model.PeriodicWork;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.util.TimeUnit2;

@Extension
public class Ec2NodeAdderTask extends PeriodicWork {

	private static final BlockingQueue<EC2AbstractSlave> nodesToAdd = new LinkedBlockingQueue<>();
	
	public static void offerAndWaitForCompletion(EC2AbstractSlave... nodes) {
		offer(nodes);
		waitForCompletion();
	}
	
	public static void offer(EC2AbstractSlave... nodes) {
		nodesToAdd.addAll(Arrays.asList(nodes));
	}
	
	public static void waitForCompletion() {
		if (nodesToAdd.isEmpty()) return;
		synchronized (nodesToAdd){
			try {
				nodesToAdd.wait();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	@Override
	public long getRecurrencePeriod() {
		return TimeUnit2.SECONDS.toMillis(1);
	}

	@Override
	protected void doRun() throws Exception {
		if (nodesToAdd.isEmpty())
			return;
		while(!nodesToAdd.isEmpty()) {
			EC2AbstractSlave nodeToAdd = nodesToAdd.take();
			Jenkins.getInstance().addNode(nodeToAdd);
		}
		
		synchronized (nodesToAdd){
			nodesToAdd.notifyAll();
		}
	}
}
