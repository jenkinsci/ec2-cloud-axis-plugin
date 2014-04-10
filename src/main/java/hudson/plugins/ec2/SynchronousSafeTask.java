package hudson.plugins.ec2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;

import org.jenkinsci.plugins.ec2axis.Ec2SafeNodeTaskWorker;

public class SynchronousSafeTask {
	final List<FutureTask<Void>> taskResult = new ArrayList<>();

	public void invoke(Runnable taskToRunOnSafeThread) {
		FutureTask<Void> result = Ec2SafeNodeTaskWorker.invoke(taskToRunOnSafeThread);
		taskResult.add(result);
		
	}

	public void waitCompletion() {
		try {
			for (FutureTask<Void> result : taskResult) 
				result.get();
		}catch(Exception e) {
			throw new RuntimeException(e);
		}
	}
}