package hudson.plugins.ec2;

import hudson.model.TaskListener;

import java.io.IOException;

import com.amazonaws.AmazonClientException;


public class Ec2AxisSlaveTemplate extends SlaveTemplate {

 	private transient String instanceLabel;

	public Ec2AxisSlaveTemplate(SlaveTemplate toDecorate) {
		super(
			 toDecorate.ami, 
			 toDecorate.zone, 
			 toDecorate.securityGroups, 
			 toDecorate.remoteFS, 
			 toDecorate.sshPort, 
			 toDecorate.type, 
			 toDecorate.getLabelString(),
			 toDecorate.mode, 
			 toDecorate.description, 
			 toDecorate.initScript, 
			 toDecorate.userData, 
			 toDecorate.numExecutors, 
			 toDecorate.remoteAdmin, 
			 toDecorate.rootCommandPrefix, 
			 toDecorate.jvmopts, 
			 toDecorate.stopOnTerminate, 
			 toDecorate.subnetId, 
			 toDecorate.getTags(), 
			 toDecorate.idleTerminationMinutes, 
			 toDecorate.usePrivateDnsName, 
			 toDecorate.getInstanceCapStr());
		instanceLabel = toDecorate.getLabelString();
	}

	public void setInstanceLabel(String label){
		instanceLabel = label;
	}
	
	@Override
	public EC2Slave provision(TaskListener listener) throws AmazonClientException, IOException {
		EC2Slave provisionedSlave = super.provision(listener);
		provisionedSlave.setLabelString(instanceLabel);
		return provisionedSlave;
	}
}
