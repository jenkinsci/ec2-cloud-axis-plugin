package org.jenkinsci.plugins.ec2axis;

import hudson.Extension;
import hudson.model.PeriodicWork;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class Ec2SafeNodeTaskWorker extends PeriodicWork {
	private static BlockingQueue<FutureTask<Void>> tasks = new LinkedBlockingQueue<>();
        private static final Logger LOGGER = Logger.getLogger(Ec2SafeNodeTaskWorker.class.getName());

	public static void invokeAndWait(final Runnable task) {
		FutureTask<Void> futureTask = invoke(task);
		try {
			futureTask.get();
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public long getRecurrencePeriod() {
		return TimeUnit.SECONDS.toMillis(1);
	}

	@Override
	protected void doRun() throws Exception {
		if (tasks.isEmpty())
			return;
		while(!tasks.isEmpty()) {
			FutureTask<Void> safeEc2Task = tasks.take();
			safeEc2Task.run();
		}
	}

	public static FutureTask<Void> invoke(final Runnable task) {
		FutureTask<Void> futureTask = new FutureTask<>(task, null);
		boolean inserted = tasks.offer(futureTask);
                if (!inserted) {
                    LOGGER.log(Level.FINE, "Failed to insert task {0} into queue", futureTask.toString());
                }
		return futureTask;
	}
		

}
