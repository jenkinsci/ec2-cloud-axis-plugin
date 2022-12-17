package org.jenkinsci.plugins.ec2axis;

import static org.kohsuke.stapler.Stapler.getCurrentRequest;
import hudson.Extension;
import hudson.Util;
import hudson.matrix.Axis;
import hudson.matrix.MatrixRun;
import hudson.matrix.AxisDescriptor;
import hudson.matrix.LabelAxis;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Messages;
import hudson.model.Label;
import hudson.model.Run;
import hudson.model.labels.LabelAtom;
import hudson.plugins.ec2.EC2AxisCloud;
import hudson.plugins.ec2.EC2Logger;
import hudson.util.FormValidation;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class EC2Axis extends LabelAxis {

	private static final Integer DEFAULT_TIMEOUT = 600;
	private Integer numberOfSlaves;
	private boolean alwaysCreateNewNodes = false;
	private final String ec2label;
	private final Integer instanceBootTimeoutLimit;
	private boolean createMatrixEnvironmentVariable = false;

	@DataBoundConstructor
	public EC2Axis(String name, String ec2label, Integer numberOfSlaves, boolean alwaysCreateNewNodes, boolean createMatrixEnvironmentVariable) {
		super(name, Arrays.asList(ec2label.trim()));
		this.setCreateMatrixEnvironmentVariable(createMatrixEnvironmentVariable);
		this.instanceBootTimeoutLimit = DEFAULT_TIMEOUT;
		this.ec2label = ec2label.trim();
		this.numberOfSlaves = numberOfSlaves;
		this.alwaysCreateNewNodes = alwaysCreateNewNodes;
	}

	public String getEc2label() {
		return ec2label;
	}
		
	public Integer getNumberOfSlaves() {
		return numberOfSlaves;
	}
	
	public void setNumberOfSlaves(Integer numberOfSlaves) {
		this.numberOfSlaves = numberOfSlaves;
	}

	public Integer getInstanceBootTimeoutLimit() {
		return instanceBootTimeoutLimit;
	}
	
	public boolean isAlwaysCreateNewNodes(){
		return alwaysCreateNewNodes;
	}

	final static ReentrantLock  labelAllocationLock = new ReentrantLock();
	
	@Override
	public List<String> rebuild(MatrixBuild.MatrixBuildExecution context) {
		EC2AxisCloud cloudToUse = getCloudToUse();
		
		addEc2Description(context, cloudToUse);

		try {
			lockForIdleNodeAllocationIfNeeded();
			return allocateNodes(context, cloudToUse);
		}finally {
			releaseLockForIdleNodeAllocation();
		}
	}

	private EC2AxisCloud getCloudToUse() {
		EC2AxisCloud cloudToUse = EC2AxisCloud.getCloudToUse(ec2label);
		if (cloudToUse == null) {
			throw new RuntimeException("Cloud for label " + ec2label + " not found.");
		}
		return cloudToUse;
	}

	private void addEc2Description(MatrixBuild.MatrixBuildExecution context, EC2AxisCloud cloudToUse) 
	{
		Ec2AxisDescriptionAction e = new Ec2AxisDescriptionAction(
				ec2label,
				numberOfSlaves,
				cloudToUse.getInstanceType(ec2label),
				cloudToUse.getSpotPriceIfApplicable(ec2label));
		context.getBuild().getActions().add(e);
	}

	public List<String> allocateNodes(MatrixBuild.MatrixBuildExecution context,
			EC2AxisCloud cloudToUse) {
		EC2Logger ec2Logger = new EC2Logger(context.getListener().getLogger());
		List<String> allocateSlavesLabels = cloudToUse.allocateSlavesLabels(
				ec2Logger, ec2label, numberOfSlaves, instanceBootTimeoutLimit, alwaysCreateNewNodes, createMatrixEnvironmentVariable
				);
		
		ec2Logger.println("Will run on the following labels:-------");
		for (String allocatedSlaveLabel : allocateSlavesLabels) {
			ec2Logger.println(allocatedSlaveLabel);
		}
		ec2Logger.println("-----------");
		
		return allocateSlavesLabels;
	}

	private void releaseLockForIdleNodeAllocation() {
		if (alwaysCreateNewNodes)
			return;
		labelAllocationLock.unlock();
	}

	public void lockForIdleNodeAllocationIfNeeded() {
		if (alwaysCreateNewNodes)
			return;
		try {
			labelAllocationLock.lockInterruptibly();
		} catch (InterruptedException e1) {
			throw new Run.RunnerAbortedException();
		}
	}

	@Override
	public List<String> getValues() {
		StaplerRequest currentRequest = getCurrentRequest();
		if (currentRequest == null)
			return makeEmptyExecution();
		
		return getConfigurationForCurrentBuildBasedOnUrl(currentRequest);
	}

	private List<String> makeEmptyExecution() {
		return Arrays.asList("cloud "+ec2label);
	}

	private List<String> getConfigurationForCurrentBuildBasedOnUrl( StaplerRequest currentRequest) 
	{
		String currentRequestUrl = currentRequest.getRequestURI();
		if (isBuildSelected(currentRequestUrl)) 
			return getConfigurationsBuildRequestedOnUrl(currentRequestUrl);
		
		return getConfigurationsForLastBuild(currentRequestUrl);
	}
	
	private List<String> getConfigurationsBuildRequestedOnUrl(String currentRequestUrl) 
	{
		final String projectAndBuildNumberExtractedFromUrl = currentRequestUrl.replaceAll(".*/job/", "");
		final String[] projectAndBuildNumber = projectAndBuildNumberExtractedFromUrl.split("/");
		final String projectName = projectAndBuildNumber[0];
		final String buildNumber = projectAndBuildNumber[1];
		
		MatrixBuild build = getProject(projectName).getBuild(buildNumber);
		return getConfigurationsForBuild(build);
	}
	
	private List<String> getConfigurationsForLastBuild(String currentRequestUrl) 
	{
		final String projectNameExtractedFromUrl = currentRequestUrl.replaceAll(".*/job/([^/]*).*", "$1");
		
		final MatrixProject matrixProject = getProject(projectNameExtractedFromUrl);
		if (matrixProject == null)
			return makeEmptyExecution();
		final MatrixBuild lastBuild = matrixProject.getLastBuild();
		
		return getConfigurationsForBuild(lastBuild);
	}
	
	private List<String> getConfigurationsForBuild(MatrixBuild build) {
		if (build == null)
			return makeEmptyExecution();
		
		final List<MatrixRun> runs = build.getRuns();
		final List<String> builtOn = new LinkedList<String>();
		for (MatrixRun matrixRun : runs) {
			if (matrixRun.getParentBuild().getNumber() == build.getNumber()) {
				String runName = matrixRun.getParent().getName();
				String configurationName = runName.replaceAll(getName()+"=([^,]*).*", "$1"); 
				builtOn.add(configurationName);
			}
		}
		if (builtOn.size() == 0)
			return makeEmptyExecution();
		return builtOn;
	}

	private boolean isBuildSelected(String requestURI) {
		return requestURI.matches(".*job//[0-9]+/?$");
	}
	
	private MatrixProject getProject(String projectName) {
		return (MatrixProject)Jenkins.getInstance().getItem(projectName);
	}

	public boolean isCreateMatrixEnvironmentVariable() {
		return createMatrixEnvironmentVariable;
	}

	public void setCreateMatrixEnvironmentVariable(
			boolean createMatrixEnvironmentVariable) {
		this.createMatrixEnvironmentVariable = createMatrixEnvironmentVariable;
	}

	@Extension
	public static class DescriptorImpl extends AxisDescriptor {
	
	    @Override
	    public String getDisplayName() {
	        return "EC2Axis";
	    }
	
	    @Override
	    public Axis newInstance(StaplerRequest req, JSONObject formData) throws FormException {
	        return new EC2Axis(
	                formData.getString("name"),
	                formData.getString("ec2label"),
	                formData.getInt("numberOfSlaves"),
	                formData.getBoolean("alwaysCreateNewNodes"),
	                formData.getBoolean("createMatrixEnvironmentVariable")
	        );
	    }
	    
	    public FormValidation doCheckEc2label(@QueryParameter String value) {
	    	String[] labels = value.split(" ");
	    	for (String oneLabel : labels) {
	    		FormValidation validation = checkOneLabel(oneLabel.trim());
				if (!validation.equals(FormValidation.ok()))
					return validation;
			}
	        return FormValidation.ok();
	    }
	
		private FormValidation checkOneLabel(String oneLabel) {
			if (Util.fixEmpty(oneLabel)==null)
				return FormValidation.ok(); 
			
			Label l = Jenkins.getInstance().getLabel(oneLabel);
                        if (l == null) {
				return FormValidation.warning("No agent/cloud matches this label expression.");
                        }
			if (l.isEmpty()) {
				for (LabelAtom a : l.listAtoms()) {
					if (a.isEmpty()) {
						LabelAtom nearest = LabelAtom.findNearest(a.getName());
						return FormValidation.warning(MessageFormat.format("No agent/cloud matches this label expression. Did you mean ‘{1}’ instead of ‘{0}’?", a.getName(), nearest.getDisplayName()));
					}
				}
				return FormValidation.warning("No agent/cloud matches this label expression.");
			}
			return FormValidation.ok();
		}
	    
	    public AutoCompletionCandidates doAutoCompleteEc2label(@QueryParameter String value) {
	        AutoCompletionCandidates c = new AutoCompletionCandidates();
	        Set<Label> labels = Jenkins.getInstance().getLabels();
	        List<String> queries = new AutoCompleteSeeder(value).getSeeds();
	
	        for (String term : queries) {
	            for (Label l : labels) {
	                if (l.getName().startsWith(term)) {
	                    c.add(l.getName());
	                }
	            }
	        }
	        return c;
	    }
	}
}
