package hudson.plugins.ec2;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;

final class OnDemandSlaveConnectionMonitor implements Runnable {
	private final Map<EC2AbstractSlave, Future<?>> connectionByLabel;
	private final EC2Logger logger;

	OnDemandSlaveConnectionMonitor( Map<EC2AbstractSlave, Future<?>> connectionByLabel, EC2Logger logger) {
		this.connectionByLabel = connectionByLabel;
		this.logger = logger;
	}

	@Override
	public void run() {
		LinkedList<EC2AbstractSlave> nodesToRetry = new LinkedList<EC2AbstractSlave>();
		for (Entry<EC2AbstractSlave, Future<?>> resultBySlave : connectionByLabel.entrySet()) {
			if (!waitForConnection(resultBySlave, true)) {
				nodesToRetry.add(resultBySlave.getKey());
			}
		}
		if (nodesToRetry.isEmpty())
			return;
		
		int retries = 10;
		while (retries > 0) {
			nodesToRetry = retryConnectionOnFailedLaunches(nodesToRetry);
			if (nodesToRetry.isEmpty())
				return;
			retries--;
		}
	}

	private LinkedList<EC2AbstractSlave> retryConnectionOnFailedLaunches(final LinkedList<EC2AbstractSlave> nodesToRetry) {
		final LinkedList<EC2AbstractSlave> failed = new LinkedList<EC2AbstractSlave>();
		Map<EC2AbstractSlave, Future<?>> reattempts = new HashMap<EC2AbstractSlave, Future<?>>();
		logger.println("Will retry connection on failed nodes in 5 secs");
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) { }
		
		for (EC2AbstractSlave ec2Slave : nodesToRetry) {
			if (ec2Slave.toComputer().isOffline()) {
				logger.println("Retrying connection on slave name " + ec2Slave.getDisplayName());
				reattempts.put(ec2Slave, ec2Slave.toComputer().connect(true));
			}
		}
		
		for (Entry<EC2AbstractSlave, Future<?>> futureRetry : reattempts.entrySet()) {
			if (!waitForConnection(futureRetry, false))
				failed.add(futureRetry.getKey());
		}
		return failed;
	}

	private boolean waitForConnection( Entry<EC2AbstractSlave, Future<?>> future, boolean retry) {
		EC2AbstractSlave ec2Slave = future.getKey();
		logger.println( String.format("Waiting %s (label %s) to come up", ec2Slave.getDisplayName(),ec2Slave.getLabelString()));
		
		try {
			future.getValue().get();
			logger.println(String.format("Slave %s (label %s) is online", 
					ec2Slave.getDisplayName(),
					ec2Slave.getLabelString()));
			return true;
		}catch(Exception e) {
			logger.println("Slave '"+ec2Slave.getDisplayName()+"' with label '"+ec2Slave.getLabelString()+"' failed to connect.");
			logger.printStackTrace(e);
			return false;
		}
	}
}