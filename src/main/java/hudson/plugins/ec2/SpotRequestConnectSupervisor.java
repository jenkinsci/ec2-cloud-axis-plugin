package hudson.plugins.ec2;

import hudson.model.Hudson;
import hudson.util.TimeUnit2;

import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.management.RuntimeErrorException;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.StopWatch;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;

final class SpotRequestConnectSupervisor implements Runnable {
	private final List<SpotInstanceRequest> reqInstances;
	private final List<EC2AbstractSlave> spotSlaves;
	private final AmazonEC2 ec2;
	private String remoteAdmin;
	private char [] privateKey;
	private PrintStream logger;

	
	public static void start(PrintStream logger, 
			List<SpotInstanceRequest> reqInstances, 
			List<EC2AbstractSlave> spotSlaves, 
			AmazonEC2 ec2, 
			char [] privateKey, 
			String remoteAdmin) {
		new Thread(new SpotRequestConnectSupervisor(logger, reqInstances, spotSlaves, ec2, privateKey, remoteAdmin)).start();
	}

	private SpotRequestConnectSupervisor(
			PrintStream logger, 
			List<SpotInstanceRequest> reqInstances,
			List<EC2AbstractSlave> spotSlaves, 
			AmazonEC2 ec2,
			char [] privateKey, 
			String remoteAdmin) {
		this.logger = logger;
		this.reqInstances = reqInstances;
		this.spotSlaves = spotSlaves;
		this.ec2 = ec2;
		this.privateKey = privateKey;
		this.remoteAdmin = remoteAdmin;
	}

	@Override 
	public void run() {
		List<String> spotInstanceRequestIds = new ArrayList<String>();
		for (SpotInstanceRequest req : reqInstances) {
			spotInstanceRequestIds.add(req.getSpotInstanceRequestId());
		}
		LinkedList<EC2AbstractSlave> remainingSlaves = new LinkedList<EC2AbstractSlave>();
		for (EC2AbstractSlave ec2AbstractSlave : spotSlaves) {
			remainingSlaves.add(ec2AbstractSlave);
		}
		
		do {
			DescribeSpotInstanceRequestsRequest describeRequest = new DescribeSpotInstanceRequestsRequest();
			describeRequest.setSpotInstanceRequestIds(spotInstanceRequestIds);
	
			connectFulfilledInstancesToJenkins(spotInstanceRequestIds, remainingSlaves, describeRequest);
	
			try {
				Thread.sleep(60 * 1000);
			} catch (Exception e) {
			}
		} while (spotInstanceRequestIds.size()>0);
	}

	private void connectFulfilledInstancesToJenkins(
			List<String> spotInstanceRequestIds,
			LinkedList<EC2AbstractSlave> remainingSlaves,
			DescribeSpotInstanceRequestsRequest describeRequest) {
		try {
			printlnWithTime("Checking whether spot requests have been fulfilled");
			DescribeSpotInstanceRequestsResult describeResult = ec2.describeSpotInstanceRequests(describeRequest);
			List<SpotInstanceRequest> describeResponses = describeResult.getSpotInstanceRequests();

			List<String> fulfilled = new LinkedList<String>();
			for (SpotInstanceRequest describeResponse : describeResponses) {
				if (describeResponse.getState().equals("open")) {
					continue;
				}
				printlnWithTime(
						"Request fulfilled: " + 
						describeResponse.getSpotInstanceRequestId() +
						" Instance id : " + describeResponse.getInstanceId()
						);
				fulfilled.add(describeResponse.getInstanceId());
				spotInstanceRequestIds.remove(describeResponse.getSpotInstanceRequestId());
			}
			
			makeInstancesConnectBackOnJenkins(fulfilled, remainingSlaves);
		} catch (Exception e) {
			e.printStackTrace(logger);
		}
	}

	private void printlnWithTime(String string) {
		logger.println(new SimpleDateFormat().format(new Date()) + " : "+string);
	}

	private void makeInstancesConnectBackOnJenkins(List<String> fulfilledInstanceIds, LinkedList<EC2AbstractSlave> remainingSlaves) 
			throws AmazonClientException, IOException {
		if (fulfilledInstanceIds.size() == 0)
			return;
		assert(remainingSlaves.size() != 0);
				
		DescribeInstancesResult describeInstances = ec2.describeInstances(new DescribeInstancesRequest().withInstanceIds(fulfilledInstanceIds) );
		List<Instance> instances = new ArrayList<Instance>();
		
		List<Reservation> reservations = describeInstances.getReservations();
		for (Reservation reservation : reservations) {
			instances.addAll(reservation.getInstances());
		}
		
		printlnWithTime("Count of instances to connect to: " + instances.size());
		for (final Instance instance : instances) {
			final EC2AbstractSlave slaveToAssociate = getSlaveToAssociate(instance.getSpotInstanceRequestId(), remainingSlaves);
			if(slaveToAssociate == null){
				String message = "SlaveToAssociate is null!!! "+instance.getInstanceId()+"/"+instance.getPrivateIpAddress();
				printlnWithTime(message);
				throw new RuntimeException(message);
			}
			printlnWithTime("Firing up connection for "+slaveToAssociate+" : "+instance.getInstanceId()+"/"+instance.getPrivateIpAddress());
			new Thread(new Runnable() {  @Override public void run() {
				associateSlaveToInstanceIpAddress(instance, slaveToAssociate);
			}}).start();
		}
		printlnWithTime("Done firing up threads to handle connections for " + StringUtils.join(fulfilledInstanceIds,", "));
	}

	private EC2AbstractSlave getSlaveToAssociate(String reqId, LinkedList<EC2AbstractSlave> remainingSlaves) {
		for (EC2AbstractSlave ec2AbstractSlave : remainingSlaves) {
			if (((EC2SpotSlave)ec2AbstractSlave).getSpotInstanceRequestId().equals(reqId)) {
				return ec2AbstractSlave;
			}
		}
		return null;
	}

	private void associateSlaveToInstanceIpAddress(Instance instance, EC2AbstractSlave slaveToAssociate) {
		String privateIpAddress = instance.getPrivateIpAddress();
		boolean success;
		long timeout = TimeUnit2.MINUTES.toMillis(20);
		int retryIntervalSecs = 5;
		long retryIntervalMillis = TimeUnit2.SECONDS.toMillis(retryIntervalSecs);
		long maxWait = System.currentTimeMillis() + timeout;
		StopWatch stopwatch = new StopWatch();
		stopwatch.start();
		printlnWithTime("Trying to connect Slave " + slaveToAssociate.getDisplayName() + " "+ slaveToAssociate.getLabelString() + " to "+privateIpAddress);
		do{
			success = tryToLaunchSlave(slaveToAssociate.getNodeName(), privateIpAddress);
			try {
				Thread.sleep(retryIntervalMillis);
			} catch (InterruptedException e) {
				printlnWithTime("InterruptedException!!");
				e.printStackTrace(logger);
			}
		} while(!success && System.currentTimeMillis() < maxWait );
		
		stopwatch.stop();
		if(!success){
			printlnWithTime("ERROR! Could not connect to "+privateIpAddress);
		}
		else {
			printlnWithTime("It took " + stopwatch.getTime() + " ms to connect to "+ privateIpAddress );
		}
	}

	private boolean tryToLaunchSlave(String slaveToAssociate, String privateIpAddress) {
		String jenkinsUrl = Hudson.getInstance().getRootUrl();
		
		try {
			Connection sshConnection = new Connection(privateIpAddress);
			sshConnection.connect(new ServerHostKeyVerifier() {
		        public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
		            return true;
		        }
		    });
			if (sshConnection.authenticateWithPublicKey(remoteAdmin, privateKey, "")) {
				printlnWithTime("Will associate slave " + slaveToAssociate + " with instance with ip " + privateIpAddress);
				
				try {
					Session openSession = sshConnection.openSession();
					String wgetCmd = "wget " + jenkinsUrl + "jnlpJars/slave.jar -O slave.jar";
					String encodedSlaveToAssociate = slaveToAssociate.replace(" ", "%20");
					String slaveLaunch = "java -jar slave.jar -jnlpUrl \"" + jenkinsUrl + "computer/" + encodedSlaveToAssociate + "/slave-agent.jnlp\"";
					String slaveLaunchCmd = "nohup " +slaveLaunch + " > slave.log 2> slave.err </dev/null &";
					
					execCommandAndWaitForCompletion(openSession, wgetCmd + " && " + slaveLaunchCmd);
					openSession.close();
					printlnWithTime("Successfully connected to "+privateIpAddress);
					return true; 
				}catch(Exception e) {
					return false;
				}
			}
			else {
				String message = "Could not connect with user " + remoteAdmin + " on " + privateIpAddress;
				printlnWithTime(message);
				throw new RuntimeException(message);
			}
		}catch(Exception e) {
			return false;
		}
	}

	private void execCommandAndWaitForCompletion(Session openSession, String cmd) throws IOException, InterruptedException {
		int timeoutForCommand = 60 * 1000 * 5;
		openSession.execCommand(cmd);
		openSession.waitForCondition(ChannelCondition.EXIT_STATUS, timeoutForCommand);
		Integer exitStatus = openSession.getExitStatus();
		if(exitStatus != 0){
			printlnWithTime("Command failed: " + cmd);
			throw new RuntimeException("Command failed: " + cmd);
		}
	}
}