package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;

public class EC2AxisCloud extends AmazonEC2Cloud {

	private static final String SLAVE_NUM_SEPARATOR = "__";

	@DataBoundConstructor
	public EC2AxisCloud(String accessId, String secretKey, String region, String privateKey, String instanceCapStr, List<SlaveTemplate> templates) {
		super(accessId,secretKey,region, privateKey,instanceCapStr,replaceByEC2AxisSlaveTemplates(templates));
	}
	
	private static List<SlaveTemplate> replaceByEC2AxisSlaveTemplates(List<SlaveTemplate> templates) {
		List<SlaveTemplate> ec2axisTemplates = new LinkedList<SlaveTemplate>();
		for (SlaveTemplate slaveTemplate : templates) {
			ec2axisTemplates.add(new Ec2AxisSlaveTemplate(slaveTemplate));
		}
		return ec2axisTemplates;
	}

	@Override
	public SlaveTemplate getTemplate(Label label) {
		String displayName = label.getDisplayName();
    	LabelAtom labelAtom = new LabelAtom(displayName.replaceAll(SLAVE_NUM_SEPARATOR+".*", ""));
    	Ec2AxisSlaveTemplate template = (Ec2AxisSlaveTemplate)super.getTemplate(labelAtom);
    	if (template == null)
    		return null;
    	template.setInstanceLabel(displayName);
		return template;
	}
	
	@Extension
    public static class DescriptorImpl extends AmazonEC2Cloud.DescriptorImpl {
        @Override
		public String getDisplayName() {
            return "EC2 Axis Amazon Cloud";
        }

    }

	public List<String> allocateSlavesLabels(String ec2Label, Integer numberOfSlaves) {
		Set<Label> labels = Jenkins.getInstance().getLabels();
		LinkedList<String> allocatedLabels = new LinkedList<String>();
		int max = 0;
		
		for (Label label : labels) {
			String displayName = label.getDisplayName();
			if (!displayName.startsWith(ec2Label))
				continue;
			
			String[] split = displayName.split(SLAVE_NUM_SEPARATOR);
			if (split.length == 1)
				continue;
			int slaveNumber = Integer.parseInt(split[1]);
			if (slaveNumber > max)
				max = slaveNumber;
			
			if (hasAvailableNode(label))
				allocatedLabels.add(displayName);
			
			if (allocatedLabels.size() >= numberOfSlaves)
				break;
		}
		
		Integer slavesToComplete = numberOfSlaves - allocatedLabels.size();
		for (int i = 0; i < slavesToComplete; i++) {
			int slaveNumber = max+i;
			allocatedLabels.add(ec2Label + SLAVE_NUM_SEPARATOR + slaveNumber);
		}
		
		return allocatedLabels;
	}

	private boolean hasAvailableNode(Label label) {
		Set<Node> nodes = label.getNodes();
		boolean hasAvailableNode = false;
		for (Node node : nodes) {
			Computer c = node.toComputer();
			boolean isNodeAvailable = (c.isOnline() || c.isConnecting()) && c.isAcceptingTasks();
			if (!isNodeAvailable)
				continue;
			List<Executor> executors = c.getExecutors();
			for (Executor executor : executors) {
				if (executor.isIdle()) {
					hasAvailableNode = true;
					break;
				}
			}
			if (hasAvailableNode)
				break;
		}
		return hasAvailableNode;
	}

	public static EC2AxisCloud getCloudToUse() {
		Iterator<Cloud> iterator = Jenkins.getInstance().clouds.iterator();
		EC2AxisCloud cloudToUse = null;
		while(iterator.hasNext()) {
			Cloud next = iterator.next();
			if (next instanceof EC2AxisCloud) {
				cloudToUse = (EC2AxisCloud) next;
			}
			
		}
		return cloudToUse;
	}

}
