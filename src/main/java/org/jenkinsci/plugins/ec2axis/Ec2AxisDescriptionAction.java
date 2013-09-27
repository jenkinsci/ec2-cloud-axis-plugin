package org.jenkinsci.plugins.ec2axis;

import java.util.List;

import org.kohsuke.stapler.export.Exported;

import hudson.model.Action;

public class Ec2AxisDescriptionAction implements Action {

	private String ec2label;
	private Integer numberOfSlaves;

	public Ec2AxisDescriptionAction(String ec2label, Integer numberOfSlaves) 
	{
		this.ec2label = ec2label;
		this.numberOfSlaves = numberOfSlaves;
	}
	
	
    public String getIconPath() { return ""; }
    public String getText() { 
    	String text = "<h4>EC2-Cloud-Axis Execution</h4>"
    			+ "<b>Cloud label:</b> " +ec2label +"<br><b>Number of Slaves used for execution:</b> " + numberOfSlaves;
    	return text; 
    	}

	@Override
	public String getIconFileName() {
		return "";
	}

	@Override
	public String getDisplayName() {
		return "";
	}

	@Override
	public String getUrlName() {
		return "";
	}
	
}
