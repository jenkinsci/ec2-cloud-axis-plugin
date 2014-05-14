package hudson.plugins.ec2.utils;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ExecutorUtils {
	public static void runBlockWithTimeoutInSeconds(final Runnable runnable, long timeout) {
		runBlockWithTimeout(runnable, timeout, TimeUnit.SECONDS);
	}
	
	public static void runBlockWithTimeout(final Runnable runnable, long timeout, TimeUnit timeUnit) {
		ExecutorService executor = Executors.newCachedThreadPool();
		Callable<Void> task = new Callable<Void>() {
		   public Void call() {
			   runnable.run();
			   return null;
		   }
		};
		Future<Void> future = executor.submit(task);
		try {
		   future.get(timeout, timeUnit); 
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		} catch (ExecutionException e) {
		   throw new RuntimeException(e);
		} catch (TimeoutException e) {
			throw new RuntimeTimeoutException(e);
		} 
	}
}
