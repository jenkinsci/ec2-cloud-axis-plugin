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

import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.kohsuke.stapler.DataBoundConstructor;

public class EC2AxisCloud extends AmazonEC2Cloud {
	private static final String SLAVE_MATRIX_ENV_VAR_NAME = "MATRIX_EXEC_ID";
	private static final String SLAVE_NUM_SEPARATOR = "__";

	@DataBoundConstructor
	public EC2AxisCloud(String accessId, String secretKey, String region, String privateKey, String instanceCapStr, List<SlaveTemplate> templates) {
		super(accessId,secretKey,region, privateKey,instanceCapStr,replaceByEC2AxisSlaveTemplates(templates));
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
		
	@SuppressWarnings("rawtypes")
	public synchronized List<String> allocateSlavesLabels(
			MatrixBuildExecution context, 
			String ec2Label, 
			Integer numberOfSlaves, 
			Integer instanceBootTimeoutLimit)
	{
		final PrintStream logger = context.getListener().getLogger();
		LinkedList<String> allocatedLabels = allocateLabels(logger, ec2Label, numberOfSlaves);
		
		Label label = null;
		Ec2AxisSlaveTemplate t = getTemplate(label);
		try {
			@SuppressWarnings("deprecation")
			List<EC2Slave> allocatedSlaves = t.provisionMultipleSlaves(new StreamTaskListener(System.out), allocatedLabels);
			Map<EC2Slave, Future> connectionByLabel = new HashMap<EC2Slave, Future>();
			Iterator<String> labelIt = allocatedLabels.iterator();
			int matrixIdSeq = 1;
			for (EC2Slave ec2Slave : allocatedSlaves) {
				Hudson.getInstance().addNode(ec2Slave);
				ec2Slave.setLabelString(labelIt.next());
				EnvVars slaveEnvVars = getSlaveEnvVars(ec2Slave);
				slaveEnvVars.put(SLAVE_MATRIX_ENV_VAR_NAME, ""+matrixIdSeq++);
				connectionByLabel.put(ec2Slave, ec2Slave.toComputer().connect(false));
			}
			for (Entry<EC2Slave, Future> future : connectionByLabel.entrySet()) {
				try {
					future.getValue().get();
				}catch(Exception e) {
					logger.println("Slave for label '"+future.getKey().getLabelString()+"' failed to connect.");
					logger.println("Slave name is: " + future.getKey().getDisplayName());
					logger.print(ExceptionUtils.getFullStackTrace(e));
				}
			}
		} catch (Exception e) {
			logger.print(ExceptionUtils.getFullStackTrace(e));
			throw new RuntimeException(e);
		}
		
		return allocatedLabels;
	}

	private EnvVars getSlaveEnvVars(EC2Slave provisionedSlave)
			throws IOException {
		EnvironmentVariablesNodeProperty v = provisionedSlave.getNodeProperties().get(EnvironmentVariablesNodeProperty.class);
		if (v == null) {
			v = new EnvironmentVariablesNodeProperty();
			provisionedSlave.getNodeProperties().add(v);
		}
		return v.getEnvVars();
	}
	
	private LinkedList<String> allocateLabels(PrintStream logger, String ec2Label, Integer numberOfSlaves) 
	{
		LinkedList<String> allocatedLabels = new LinkedList<String>();
		int lastAllocatedSlaveNumber = 0;
		logger.println("Starting selection of labels with idle executors for job");
		LinkedList<String> idleLabels = new LinkedList<String>();
		
		TreeSet<Label> sortedLabels = getSortedLabels();
		
		for (Label label : sortedLabels) {
			String labelString = label.getDisplayName();
			if (!labelString.startsWith(ec2Label)) {
				continue;
			}
			
			final String[] prefixAndSlaveNumber = labelString.split(SLAVE_NUM_SEPARATOR);
			boolean hasNoSuffix = prefixAndSlaveNumber.length == 1;
			if (hasNoSuffix)
				continue;
			
			String suffix = prefixAndSlaveNumber[1];
			int slaveNumber = Integer.parseInt(suffix);
			if (slaveNumber > lastAllocatedSlaveNumber)
				lastAllocatedSlaveNumber = slaveNumber;
			
			if (!hasAvailableNode(logger, label)) {
				logger.println(labelString + " not available.");
				continue;
			}
			idleLabels.add(labelString);
			
			if (idleLabels.size() >= numberOfSlaves)
				break;
		}
		lastAllocatedSlaveNumber++;
		
		allocatedLabels.addAll(idleLabels);
		
		logger.println("Starting creation of new labels to assign");
		Integer slavesToComplete = numberOfSlaves - allocatedLabels.size();
		for (int i = 0; i < slavesToComplete; i++) {
			int slaveNumber = lastAllocatedSlaveNumber+i;
			String newLabel = ec2Label + SLAVE_NUM_SEPARATOR + slaveNumber;
			allocatedLabels.add(newLabel);
			logger.println("New label " + newLabel + " will be created.");
		}
		return allocatedLabels;
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
		return  isLabelAvailable(logger, label, nodes);
	}

	private boolean isLabelAvailable(PrintStream logger, Label label, Set<Node> nodes) {
		if (nodes.size() == 0)
			return true;
		logger.append(label.getDisplayName()+": label has nodes\n");
		for (Node node : nodes) {
			String nodeName = node.getDisplayName();
			logger.append("Checking node : " + nodeName + "+\n");
			Computer c = node.toComputer();
			if (c.isOffline() && !c.isConnecting()) {
				return true;
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
}
