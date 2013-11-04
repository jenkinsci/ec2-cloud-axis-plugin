package hudson.plugins.ec2;

import hudson.model.Computer;

import java.util.List;
import java.util.concurrent.Future;

final class OnDemandSlaveLauncher implements Runnable {

	private static final int MAX_RETRIES = 10;
	private final EC2Logger logger;
	private EC2AbstractSlave slave;
	private Exception connectionFailed;

	public OnDemandSlaveLauncher(EC2AbstractSlave slave, EC2Logger logger) {
		this.slave = slave;
		this.logger = logger;
	}

	@Override
	public void run() {
		int retries = MAX_RETRIES;
		Future<?> connectionPromise;
		String displayName = slave.getDisplayName();
		do {
			Computer computer = slave.toComputer();
			connectionPromise = computer.connect(false);
			if (waitForConnection(connectionPromise))
				return;
			try {
				logger.println("Connection to " + displayName + " failed. Will retry in 5 secons");
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				logger.printStackTrace(e);
				return;
			}
			retries--;
		}while(retries > 0);
		logger.printStackTrace(new RuntimeException("Slave"+displayName+" failed to come up after " + MAX_RETRIES + " retries",connectionFailed));
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