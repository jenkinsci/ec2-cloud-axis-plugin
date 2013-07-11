package org.jenkinsci.plugins.ec2axis;

import hudson.Extension;
import hudson.Util;
import hudson.matrix.Axis;
import hudson.matrix.AxisDescriptor;
import hudson.matrix.LabelAxis;
import hudson.matrix.MatrixBuild;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Messages;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import hudson.plugins.ec2.EC2AxisCloud;
import hudson.util.FormValidation;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class EC2Axis extends LabelAxis {

	final private Integer numberOfSlaves;


	@DataBoundConstructor
	public EC2Axis(String name, String ec2label, Integer numberOfSlaves ) {
		super(name, Arrays.asList(ec2label));
		this.numberOfSlaves = numberOfSlaves;
	}


	@Override
	public List<String> rebuild(MatrixBuild.MatrixBuildExecution context) {
		String ec2Label = getValues().get(0).trim();
		EC2AxisCloud cloudToUse = EC2AxisCloud.getCloudToUse();
		List<String> actualLabels = cloudToUse.allocateSlavesLabels(ec2Label, numberOfSlaves); 
		
		return actualLabels;
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
                    formData.getString("labelString"),
                    formData.getInt("numberOfSlaves")
            );
        }
        
        public FormValidation doCheckLabelString(@QueryParameter String value) {
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
			if (l.isEmpty()) {
				for (LabelAtom a : l.listAtoms()) {
					if (a.isEmpty()) {
						LabelAtom nearest = LabelAtom.findNearest(a.getName());
						return FormValidation.warning(Messages.AbstractProject_AssignedLabelString_NoMatch_DidYouMean(a.getName(),nearest.getDisplayName()));
					}
				}
				return FormValidation.warning(Messages.AbstractProject_AssignedLabelString_NoMatch());
			}
			return FormValidation.ok();
		}
        
        public AutoCompletionCandidates doAutoCompleteLabelString(@QueryParameter String value) {
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
