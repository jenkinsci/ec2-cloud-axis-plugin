package hudson.plugins.ec2;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Api;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.slaves.EnvironmentVariablesNodeProperty;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
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
	
	public Api getApi() {
        return new Api(this);
    }
		
	public boolean acceptsLabel(Label label) {
		return getTemplateGivenLabel(label) != null;
	}
	
	@Override
	public Ec2AxisSlaveTemplate getTemplate(Label label) {
		String displayName = label.getDisplayName();
		
    	String labelPrefix = StringUtils.substringBefore(displayName,SLAVE_NUM_SEPARATOR);
		LabelAtom prefixAtom = new LabelAtom(labelPrefix);
    	Ec2AxisSlaveTemplate template = (Ec2AxisSlaveTemplate)super.getTemplate(prefixAtom);
    	if (template == null)
    		return null;
    	template.setInstanceLabel(displayName);
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
	
	public List<String> allocateSlavesLabels(
			final EC2Logger logger, 
			String ec2Label, 
			Integer numberOfSlaves, 
			Integer instanceBootTimeoutLimit, 
			boolean alwaysCreateNewNodes, 
			boolean createMatrixEnvironmentVariable) 
	{
		List<EC2AbstractSlave> onlineAndAvailableSlaves = determineOnlineAndAvailableSlaves(
				logger, ec2Label, numberOfSlaves, alwaysCreateNewNodes);
		
		int countOfRemainingLabelsToCreate = numberOfSlaves - onlineAndAvailableSlaves.size();
		LinkedList<EC2AbstractSlave> allSlaves = new LinkedList<EC2AbstractSlave>();
		allSlaves.addAll(onlineAndAvailableSlaves);

		if (countOfRemainingLabelsToCreate > 0) {
			List<EC2AbstractSlave> newSlaves = createMissingSlaves( logger, ec2Label, countOfRemainingLabelsToCreate);
			allSlaves.addAll(newSlaves);
		}
		
		createMatrixEnvironmentVariableForAllocatedSlaves( createMatrixEnvironmentVariable, allSlaves);
		
		List<String> slaveLabels = new ArrayList<String>();
		for (EC2AbstractSlave slave : allSlaves) 
			slaveLabels.add(slave.getNodeName());
		
		return slaveLabels;
	}

	public void createMatrixEnvironmentVariableForAllocatedSlaves(
			boolean createMatrixEnvironmentVariable,
			LinkedList<EC2AbstractSlave> allSlaves) {
		if (createMatrixEnvironmentVariable) {
			int matrixId = 1;
			for (EC2AbstractSlave ec2AbstractSlave : allSlaves) {
				EnvVars slaveEnvVars = getSlaveEnvVars(ec2AbstractSlave);
				slaveEnvVars.put(SLAVE_MATRIX_ENV_VAR_NAME, matrixId+"");
				matrixId++;
			}
		}
	}

	private List<EC2AbstractSlave> determineOnlineAndAvailableSlaves(
			final EC2Logger logger, String ec2Label, Integer numberOfSlaves,
			boolean alwaysCreateNewNodes) {
		if (alwaysCreateNewNodes){
			logger.println("Will create new nodes for each slave ");
			return new ArrayList<EC2AbstractSlave>();
		}
		return findOnlineEligibleSlavesToAllocate(logger, ec2Label, numberOfSlaves);
	}

	private List<EC2AbstractSlave> createMissingSlaves(
			EC2Logger logger, 
			String ec2Label, 
			int remainingLabelsToCreate) 
	{
		try {
			return allocateSlavesAndLaunchThem(ec2Label, logger, remainingLabelsToCreate);
		} catch (Exception e) {
			logger.printStackTrace(e);
			throw new RuntimeException(e);
		}
	}
	
	private List<EC2AbstractSlave> allocateSlavesAndLaunchThem(
			String ec2Label,
			final EC2Logger logger, 
			int remainingLabelsToCreate) throws IOException 
	{
		logger.println("Will provision instances for label: " + ec2Label);
		Ec2AxisSlaveTemplate slaveTemplate = getTemplate(new LabelAtom(ec2Label));
		
		List<EC2AbstractSlave> allocatedSlaves = slaveTemplate.provisionMultipleSlaves(logger, remainingLabelsToCreate);
		 
		for (EC2AbstractSlave ec2Slave : allocatedSlaves) 
			ec2Slave.setLabelString(getAxisLabel(ec2Label));
		
		return allocatedSlaves;
	}

	private String getAxisLabel(String ec2Label) {
		return ec2Label+"$axis";
	}

	private EnvVars getSlaveEnvVars(EC2AbstractSlave provisionedSlave) {
		EnvironmentVariablesNodeProperty v = provisionedSlave.getNodeProperties().get(EnvironmentVariablesNodeProperty.class);
		if (v == null) {
			v = new EnvironmentVariablesNodeProperty();
			provisionedSlave.getNodeProperties().add(v);
		}
		return v.getEnvVars();
	}
	
	private LinkedList<EC2AbstractSlave> findOnlineEligibleSlavesToAllocate(
			EC2Logger logger,
			String ec2Label, 
			Integer numberOfSlaves) 
	{
		logger.println("Starting selection of labels with idle executors for job");
		final LinkedList<EC2AbstractSlave> onlineAndAvailableLabels = new LinkedList<EC2AbstractSlave>();
		logger.println("Will check " + ec2Label);
		
		Label label = Jenkins.getInstance().getLabel(getAxisLabel(ec2Label));
		if (label == null) {
			logger.println("Null label for " + ec2Label);
			return onlineAndAvailableLabels;
		}
		
		for (Node node : label.getNodes()) {
			if(!isNodeAvailable(logger, node)) 
				continue;
			
			EC2AbstractSlave ec2AbstractSlave = (EC2AbstractSlave) node;
			onlineAndAvailableLabels.add(ec2AbstractSlave);
			
			if (onlineAndAvailableLabels.size() >= numberOfSlaves)
				break;
		}
		
		logger.println("Online labels found : " + onlineAndAvailableLabels.size());
		return onlineAndAvailableLabels;
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
		
    	String labelPrefix = StringUtils.substringBefore(displayName,SLAVE_NUM_SEPARATOR);
		LabelAtom prefixAtom = new LabelAtom(labelPrefix);
    	Ec2AxisSlaveTemplate template = (Ec2AxisSlaveTemplate)super.getTemplate(prefixAtom);
		return template;
	}
	
	private boolean isNodeAvailable(EC2Logger logger, Node node) {
		String nodeName = node.getDisplayName();
		logger.println("Checking node : " + nodeName);
		Computer c = node.toComputer();
		if (c == null || c.isOffline() || c.isConnecting())
			return false;
		if (isNodeOnlineAndAvailable(c) && hasAvailableExecutor(c))
			return true;
		
		return false;
	}

	private boolean hasAvailableExecutor(Computer c) {
		final List<Executor> executors = c.getExecutors();
		for (Executor executor : executors) {
			if (executor.isIdle()) 
				return true;
		}
		return false;
	}

	private boolean isNodeOnlineAndAvailable(Computer c) {
		return c.isOnline() && c.isAcceptingTasks();
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

	public String getSpotPriceIfApplicable(String ec2Label) {
		Ec2AxisSlaveTemplate slaveTemplate = getTemplate(new LabelAtom(ec2Label));
		if (slaveTemplate.getSpotMaxBidPrice() == null)
			return null;
		return slaveTemplate.getCurrentSpotPrice();
	}

	public String getInstanceType(String ec2Label) {
		Ec2AxisSlaveTemplate slaveTemplate = getTemplate(new LabelAtom(ec2Label));
		return slaveTemplate.type.name();
	}

	public static long getTimeout(EC2AbstractSlave slave) {
		if (slave.getLaunchTimeoutInMillis() == 0)
			return TimeUnit.MINUTES.toMillis(20);
		return slave.getLaunchTimeoutInMillis();
	}
}
