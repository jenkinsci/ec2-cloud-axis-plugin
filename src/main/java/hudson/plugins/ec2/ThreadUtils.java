package hudson.plugins.ec2;

public class ThreadUtils {

	public static void sleepWithoutInterruptions(long milliseconds) {
		try {
			Thread.sleep(milliseconds);
		} catch (final InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}
}
