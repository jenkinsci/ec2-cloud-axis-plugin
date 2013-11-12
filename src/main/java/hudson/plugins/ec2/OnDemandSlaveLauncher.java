package hudson.plugins.ec2;

import hudson.util.TimeUnit2;

import java.util.List;
import java.util.concurrent.Future;

import org.apache.commons.lang.time.StopWatch;

final class OnDemandSlaveLauncher implements Runnable {

	private final EC2Logger logger;
	private EC2AbstractSlave slave;
	private Exception connectionFailed;

	public OnDemandSlaveLauncher(EC2AbstractSlave slave, EC2Logger logger) {
		this.slave = slave;
		this.logger = logger;
	}

	@Override
	public void run() {
		long timeout = EC2AxisCloud.getTimeout(slave);
		int retryIntervalSecs = 5;
		long retryIntervalMillis = TimeUnit2.SECONDS.toMillis(retryIntervalSecs);
		long maxWait = System.currentTimeMillis() + timeout;
		StopWatch stopwatch = new StopWatch();
		stopwatch.start();
		
		Future<?> connectionPromise;
		String displayName = slave.getDisplayName();
		do {
			connectionPromise = slave.toComputer().connect(false);
			if (waitForConnection(connectionPromise))
				return;
			try {
				logger.println("Connection to " + displayName + " failed. Will retry in "+retryIntervalSecs+" seconds");
				Thread.sleep(retryIntervalMillis);
			} catch (InterruptedException e) {
				logger.printStackTrace(e);
				return;
			}
		}
		while(System.currentTimeMillis() < maxWait);
		EC2AxisCloud.finishSlaveAndQueuedItems(slave);
		
		logger.printStackTrace(new RuntimeException("Slave"+displayName+" failed to come up after " + timeout + " ms",connectionFailed));
	}

	private boolean waitForConnection(Future<?> connectionPromise) {
		logger.println( String.format("Waiting %s (label %s) to come up", slave.getDisplayName(), slave.getLabelString()));
		
		try {
			connectionPromise.get();
			logger.println(String.format("Slave %s (label %s) is online", 
					slave.getDisplayName(),
					slave.getLabelString()));
			return true;
		}catch(Exception e) {
			logger.println("Slave '"+slave.getDisplayName()+"' with label '"+slave.getLabelString()+"' failed to connect."
					+ "\n"
					+ "The instance is probably still initializing."
					);
			connectionFailed = e;
			return false;
		}
	}

	public static void launchSlaves(List<EC2AbstractSlave> allocatedSlaves, EC2Logger logger2) {
		for (EC2AbstractSlave slaveToLaunch : allocatedSlaves) {
			new Thread(new OnDemandSlaveLauncher(slaveToLaunch, logger2)).start();
		}
	}
}