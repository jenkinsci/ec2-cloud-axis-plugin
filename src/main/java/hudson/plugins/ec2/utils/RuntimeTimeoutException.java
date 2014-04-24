package hudson.plugins.ec2.utils;

import java.util.concurrent.TimeoutException;

public class RuntimeTimeoutException extends RuntimeException {
	private static final long serialVersionUID = 8082876626184008616L;

	public RuntimeTimeoutException(String message) {
		super(message);
	}
	
	public RuntimeTimeoutException(String message, TimeoutException e) {
		super(message,e);
	}
	
	public RuntimeTimeoutException(TimeoutException e) {
		super(e);
	}
}