package hudson.plugins.ec2;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;

public class AmazonEC2Insistent implements InvocationHandler {
	private static final int MAX_RETRIES = 5;
	
	private final AmazonEC2 delegate;
	private final int waitTimeToRetryInSeconds;
	private final EC2Logger logger;
	
	public static AmazonEC2 wrap(AmazonEC2 ec2, EC2Logger logger) {
		return wrap(ec2,logger, 4);
	}
	
	public static AmazonEC2 wrap(AmazonEC2 ec2, EC2Logger logger, int waitTimeToRetryInSeconds) {
		AmazonEC2Insistent handler = new AmazonEC2Insistent(ec2, logger, waitTimeToRetryInSeconds);
		return (AmazonEC2) Proxy.newProxyInstance(AmazonEC2.class.getClassLoader(),	new Class<?>[]{AmazonEC2.class}, handler);
	}

	private AmazonEC2Insistent(AmazonEC2 delegate, EC2Logger logger, int waitTimeToRetryInSeconds) {
		this.delegate = delegate;
		this.logger = logger;
		this.waitTimeToRetryInSeconds = waitTimeToRetryInSeconds;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		int retryCount = 0;
		while (true) {
			try {
				return method.invoke(delegate, args);
			
			} catch (InvocationTargetException invocationException) {
				Throwable ex = invocationException.getTargetException();
				if (ex instanceof AmazonServiceException) {
					handleAwsException((AmazonServiceException) ex, retryCount, method.getName());
	                retryCount++;
				} else {				
					throw ex;
				}
			}
		}
	}

	private void handleAwsException(AmazonServiceException ex, int retryCount, String methodName) {
		if (retryCount > MAX_RETRIES || ex.getStatusCode() != 503)
			throw ex;
		
		logger.println("Error 503 (" + ex.getMessage() + ") calling " + methodName + ". Retry...");
		threadSleep(waitTimeToRetryInSeconds * retryCount);
    }

	private void threadSleep(int sec) {
		try {
        	Thread.sleep(1000 * sec);
        } catch (InterruptedException ex1) {
        	logger.println("Retry sleep interrupted");
        }
	}


}
