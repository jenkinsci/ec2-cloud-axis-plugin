package hudson.plugins.ec2;

import hudson.EnvVars;
import hudson.Extension;
import hudson.matrix.MatrixBuild.MatrixBuildExecution;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.util.StreamTaskListener;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.time.StopWatch;
import org.kohsuke.stapler.DataBoundConstructor;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.KeyPair;

public class EC2AxisCloud extends AmazonEC2Cloud {
	private static final String SLAVE_MATRIX_ENV_VAR_NAME = "MATRIX_EXEC_ID";
	private static final String SLAVE_NUM_SEPARATOR = "__";
	private final EC2AxisPrivateKey ec2PrivateKey;

	@DataBoundConstructor
	public EC2AxisCloud(String accessId, String secretKey, String region, String privateKey, String instanceCapStr, List<SlaveTemplate> templates) {
		super(accessId,secretKey,region, privateKey,instanceCapStr,replaceByEC2AxisSlaveTemplates(templates));
		ec2PrivateKey = new EC2AxisPrivateKey(privateKey);
	}
	
	public boolean acceptsLabel(Label label) {
		return getTemplateGivenLabel(label) != null;
	}
	
	@Override
	public Ec2AxisSlaveTemplate getTemplate(Label label) {
		String displayName = label.getDisplayName();
		if (displayName == null)
			return null;
		
    	String labelPrefix = StringUtils.substringBefore(displayName,SLAVE_NUM_SEPARATOR);
		LabelAtom prefixAtom = new LabelAtom(labelPrefix);
    	Ec2AxisSlaveTemplate template = (Ec2AxisSlaveTemplate)super.getTemplate(prefixAtom);
    	if (template == null)
    		return null;
		return template;		
	}

	public static EC2AxisCloud getCloudToUse(String ec2label) {
		Iterator<Cloud> iterator = Jenkins.getInstance().clouds.iterator();
		EC2AxisCloud cloudToUse = null;
		while(iterator.hasNext()) {
			Cloud next = iterator.next();
			if (next instanceof EC2AxisCloud) {
				if (((EC2AxisCloud)next).acceptsLabel(new LabelAtom(ec2label)))
					cloudToUse = (EC2AxisCloud) next;
			}
		}
		return cloudToUse;
	}

	final static ReentrantLock  labelAllocationLock = new ReentrantLock();
	
	public List<String> allocateSlavesLabels(
			MatrixBuildExecution context, 
			String ec2Label, 
			Integer numberOfSlaves, 
			Integer instanceBootTimeoutLimit) 
	{
		try {
			labelAllocationLock.lockInterruptibly();
			
			final PrintStream logger = context.getListener().getLogger();
			LinkedList<String> onlineAndAvailableLabels = allocateOnlineSlaves(logger, ec2Label, numberOfSlaves);
			int countOfRemainingLabelsToCreate = numberOfSlaves - onlineAndAvailableLabels.size();
			LinkedList<String> allLabels = new LinkedList<String>();
			allLabels.addAll(onlineAndAvailableLabels);

			if (countOfRemainingLabelsToCreate > 0) {
				int nextMatrixId = onlineAndAvailableLabels.size()+1;
				LinkedList<String> newLabels = createNewSlaveAndWaitUntilAllAreConnected(
						ec2Label, 
						countOfRemainingLabelsToCreate, 
						nextMatrixId, 
						logger);
				allLabels.addAll(newLabels);
			}
			
			return allLabels;
		} catch (InterruptedException e1) {
			throw new RuntimeException(e1);
		} finally {
			labelAllocationLock.unlock();
		}
	}

	@SuppressWarnings("rawtypes")
	private LinkedList<String> createNewSlaveAndWaitUntilAllAreConnected(
			String ec2Label, 
			int remainingLabelsToCreate,
			int nextMatrixId,  
			final PrintStream logger) 
	{
		LinkedList<String> newLabels = allocateNewLabels(ec2Label, remainingLabelsToCreate, logger);
		StopWatch stopWatch = new StopWatch();
		stopWatch.start();
		try {
			Map<EC2Slave, Future> connectionByLabel = allocateSlavesAndLaunchThem(ec2Label, logger, newLabels, nextMatrixId);
			waitUntilSlavesAreReady(logger, connectionByLabel);
			stopWatch.stop();
			logger.println("All slaves are up and running. It took " + stopWatch.getTime() + "ms to start all instances.");
		} catch (Exception e) {
			logger.print(ExceptionUtils.getFullStackTrace(e));
			throw new RuntimeException(e);
		}
		return newLabels;
	}

	@SuppressWarnings("rawtypes")
	private void waitUntilSlavesAreReady(final PrintStream logger, Map<EC2Slave, Future> connectionByLabel) 
	{
		for (Entry<EC2Slave, Future> future : connectionByLabel.entrySet()) {
			EC2Slave ec2Slave = future.getKey();
			logger.println(
					String.format("Waiting %s (label %s) to come up",
					ec2Slave.getDisplayName(),ec2Slave.getLabelString()));
			
			try {
				future.getValue().get();
				logger.println(String.format("Slave %s (label %s) is online", 
						ec2Slave.getDisplayName(),
						ec2Slave.getLabelString()));
			}catch(Exception e) {
				logger.println("Slave for label '"+ec2Slave.getLabelString()+"' failed to connect.");
				logger.println("Slave name is: " + ec2Slave.getDisplayName());
				logger.print(ExceptionUtils.getFullStackTrace(e));
			}
		}
	}

	@SuppressWarnings("rawtypes")
	private Map<EC2Slave, Future> allocateSlavesAndLaunchThem(
			String ec2Label,
			final PrintStream logger, 
			LinkedList<String> allocatedLabels, 
			int nextMatrixId) throws IOException 
	{
		logger.println("Will provision instances for requested labels: " + StringUtils.join(allocatedLabels,","));
		Ec2AxisSlaveTemplate t = getTemplate(new LabelAtom(ec2Label));
		@SuppressWarnings("deprecation")
		List<EC2Slave> allocatedSlaves = t.provisionMultipleSlaves(new StreamTaskListener(System.out), allocatedLabels);
		Iterator<String> labelIt = allocatedLabels.iterator();
		int matrixIdSeq = nextMatrixId;
		Map<EC2Slave, Future> connectionByLabel = new HashMap<EC2Slave, Future>();
		for (EC2Slave ec2Slave : allocatedSlaves) {
			logger.println("Setting up labels and environment variables for " + ec2Slave.getDisplayName());
			Hudson.getInstance().addNode(ec2Slave);
			ec2Slave.setLabelString(labelIt.next());
			EnvVars slaveEnvVars = getSlaveEnvVars(ec2Slave);
			slaveEnvVars.put(SLAVE_MATRIX_ENV_VAR_NAME, ""+matrixIdSeq++);
			connectionByLabel.put(ec2Slave, ec2Slave.toComputer().connect(false));
		}
		return connectionByLabel;
	}

	private EnvVars getSlaveEnvVars(EC2Slave provisionedSlave) {
		EnvironmentVariablesNodeProperty v = provisionedSlave.getNodeProperties().get(EnvironmentVariablesNodeProperty.class);
		if (v == null) {
			v = new EnvironmentVariablesNodeProperty();
			provisionedSlave.getNodeProperties().add(v);
		}
		return v.getEnvVars();
	}
	
	private synchronized LinkedList<String> allocateNewLabels(String ec2Label, Integer numberOfSlaves, PrintStream logger) 
	{
		LinkedList<String> allocatedLabels = new LinkedList<String>();
		logger.println("Starting creation of new labels to assign");
		Integer slavesToComplete = numberOfSlaves - allocatedLabels.size();
		int currentLabelNumber = 0;
		for (int i = 0; i < slavesToComplete; i++) {
			// TODO: make a method to return the next "available" label number
			int slaveNumber = currentLabelNumber++;
			String newLabel = ec2Label + SLAVE_NUM_SEPARATOR + slaveNumber;
			allocatedLabels.add(newLabel);
			logger.println("New label " + newLabel + " will be created.");
		}
		return allocatedLabels;
	}

	private LinkedList<String> allocateOnlineSlaves(
			PrintStream logger,
			String ec2Label, 
			Integer numberOfSlaves) 
	{
		logger.println("Starting selection of labels with idle executors for job");
		LinkedList<String> onlineAndAvailableLabels = new LinkedList<String>();
		TreeSet<Label> sortedLabels = getSortedLabels();
		int matrixId = 1;
		for (Label label : sortedLabels) {
			String labelString = label.getDisplayName();
			if (!labelString.startsWith(ec2Label)) {
				continue;
			}
			
			final String[] prefixAndSlaveNumber = labelString.split(SLAVE_NUM_SEPARATOR);
			boolean hasNoSuffix = prefixAndSlaveNumber.length == 1;
			if (hasNoSuffix)
				continue;
			
			if (!hasAvailableNode(logger, label)) {
				logger.println(labelString + " not available.");
				continue;
			}
			logger.println(labelString + " has online and available nodes.");
			Node firstNode = label.getNodes().iterator().next();
			if (!(firstNode instanceof EC2Slave))
				continue;
			EnvVars slaveEnvVars = getSlaveEnvVars((EC2Slave) firstNode);
			slaveEnvVars.put(SLAVE_MATRIX_ENV_VAR_NAME, matrixId+"");
			onlineAndAvailableLabels.add(labelString);
			matrixId++;
			
			if (onlineAndAvailableLabels.size() >= numberOfSlaves)
				break;
		}
		return onlineAndAvailableLabels;
	}

	private TreeSet<Label> getSortedLabels() {
		Set<Label> labels = Jenkins.getInstance().getLabels();
		TreeSet<Label> sortedLabels = new TreeSet<Label>(new Comparator<Label>() {

			@Override
			public int compare(Label o1, Label o2) {
				return o1.getDisplayName().compareTo(o2.getDisplayName());
			}
		});
		sortedLabels.addAll(labels);
		return sortedLabels;
	}

	private static List<SlaveTemplate> replaceByEC2AxisSlaveTemplates(List<SlaveTemplate> templates) {
		List<SlaveTemplate> ec2axisTemplates = new LinkedList<SlaveTemplate>();
		for (SlaveTemplate slaveTemplate : templates) {
			ec2axisTemplates.add(new Ec2AxisSlaveTemplate(slaveTemplate));
		}
		return ec2axisTemplates;
	}

	private Ec2AxisSlaveTemplate getTemplateGivenLabel(Label label) {
		String displayName = label.getDisplayName();
		if (displayName == null)
			return null;
		
    	String labelPrefix = StringUtils.substringBefore(displayName,SLAVE_NUM_SEPARATOR);
		LabelAtom prefixAtom = new LabelAtom(labelPrefix);
    	Ec2AxisSlaveTemplate template = (Ec2AxisSlaveTemplate)super.getTemplate(prefixAtom);
		return template;
	}
	

	private boolean hasAvailableNode(PrintStream logger, Label label) {
		Set<Node> nodes = label.getNodes();
		return  hasNodeOnlineAndAvailable(logger, label, nodes);
	}

	private boolean hasNodeOnlineAndAvailable(PrintStream logger, Label label, Set<Node> nodes) {
		if (nodes.size() == 0)
			return false;
		logger.append(label.getDisplayName()+": label has nodes\n");
		for (Node node : nodes) {
			String nodeName = node.getDisplayName();
			logger.append("Checking node : " + nodeName + "+\n");
			Computer c = node.toComputer();
			if (c.isOffline() && !c.isConnecting()) {
				continue;
			}
			if (isNodeOnlineAndAvailable(c))
				return true;
			
			if (hasAvailableExecutor(c))
				return true;
			logger.append(nodeName + " node not available." );
		}
		return false;
	}

	private boolean hasAvailableExecutor(Computer c) {
		final List<Executor> executors = c.getExecutors();
		for (Executor executor : executors) {
			if (executor.isIdle()) {
				return true;
			}
		}
		return false;
	}

	private boolean isNodeOnlineAndAvailable(Computer c) {
		return (c.isOnline() || c.isConnecting()) && c.isAcceptingTasks();
	}

	@Extension
	public static class DescriptorImpl extends AmazonEC2Cloud.DescriptorImpl {
	    @Override
		public String getDisplayName() {
	        return "EC2 Axis Amazon Cloud";
	    }
	}

	public KeyPair getKeyPair(AmazonEC2 ec2) throws AmazonClientException, IOException {
		return ec2PrivateKey.find(ec2);
	}
}
