package hudson.plugins.ec2;

import hudson.util.TimeUnit2;

import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public class EC2LazyRetentionStrategy extends EC2RetentionStrategy {
	
	private ReentrantLock checkProtection = new ReentrantLock();

	public EC2LazyRetentionStrategy(long idleTerminationMinutes) {
		super(idleTerminationMinutes+"");
	}

	@Override
	public long check(EC2Computer c) {
		try {
			if (!checkProtection.tryLock())
				return 1;
			return nonSynchronizedCheck(c);
		}finally {
			checkProtection.unlock();
		}
	}

	private long nonSynchronizedCheck(EC2Computer c) {
        if  (idleTerminationMinutes == 0)
        	return 1;
        
        if (c.isIdle() && c.isOnline() && !disabled) {
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(idleTerminationMinutes)) {
                LOGGER.info("Idle timeout: "+c.getName());
                c.getNode().idleTimeout();
            }
        }
        return 1;
	}
	private static final Logger LOGGER = Logger.getLogger(EC2RetentionStrategy.class.getName());
}
