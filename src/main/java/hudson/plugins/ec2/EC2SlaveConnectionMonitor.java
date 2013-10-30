package hudson.plugins.ec2;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;

import org.apache.commons.lang.exception.ExceptionUtils;

final class EC2SlaveConnectionMonitor implements Runnable {
	private final Map<EC2AbstractSlave, Future<?>> connectionByLabel;
	private final PrintStream logger;

	EC2SlaveConnectionMonitor( Map<EC2AbstractSlave, Future<?>> connectionByLabel, PrintStream logger) {
		this.connectionByLabel = connectionByLabel;
		this.logger = logger;
	}

	@Override
	public void run() {
		final LinkedList<EC2AbstractSlave> nodesToRetry = new LinkedList<EC2AbstractSlave>();
		for (Entry<EC2AbstractSlave, Future<?>> resultBySlave : connectionByLabel.entrySet()) {
			if (!waitForConnection(resultBySlave, true)) {
				nodesToRetry.add(resultBySlave.getKey());
			}
		}
		if (nodesToRetry.size() == 0)
			return;
		
		retryConnectionOnFailedLaunches(nodesToRetry);
	}

	private void retryConnectionOnFailedLaunches(final LinkedList<EC2AbstractSlave> nodesToRetry) {
		Map<EC2AbstractSlave, Future<?>> reattempts = new HashMap<EC2AbstractSlave, Future<?>>();
		try {
			logger.println("Will retry connection on failed nodes in 5 secs");
			Thread.sleep(5000);
			
			for (EC2AbstractSlave ec2Slave : nodesToRetry) {
				logger.println("Retrying connection on slave name " + ec2Slave.getDisplayName());
				reattempts.put(ec2Slave, ec2Slave.toComputer().connect(true));
			}
			
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		for (Entry<EC2AbstractSlave, Future<?>> futureRetry : reattempts.entrySet()) {
			waitForConnection(futureRetry, false);
		}
	}

	private boolean waitForConnection(
			Entry<EC2AbstractSlave, Future<?>> future,
			boolean retry) {
		EC2AbstractSlave ec2Slave = future.getKey();
		logger.println(
				String.format("Waiting %s (label %s) to come up",
				ec2Slave.getDisplayName(),ec2Slave.getLabelString()));
		
		try {
			future.getValue().get();
			logger.println(String.format("Slave %s (label %s) is online", 
					ec2Slave.getDisplayName(),
					ec2Slave.getLabelString()));
			return true;
		}catch(Exception e) {
			logger.println("Slave for label '"+ec2Slave.getLabelString()+"' failed to connect.");
			logger.println("Slave name is: " + ec2Slave.getDisplayName());
			logger.print(ExceptionUtils.getFullStackTrace(e));
			return false;
		}
	}
}