package org.jenkinsci.plugins.ec2axis;

import hudson.model.Action;

public class Ec2AxisDescriptionAction implements Action {

	private String ec2label;
	private Integer numberOfSlaves;
	private String currentBidPrice;
	private String instanceType;

	public Ec2AxisDescriptionAction(String ec2label, Integer numberOfSlaves, String instanceType, String currentBidPrice) 
	{
		this.ec2label = ec2label;
		this.numberOfSlaves = numberOfSlaves;
		this.instanceType = instanceType;
		this.currentBidPrice = currentBidPrice;
	}
	
	
    public String getIconPath() { return ""; }
    public String getText() { 
    	String text = 
    			"<h4>EC2-Cloud-Axis Execution</h4>" + 
    			"<b>Cloud label:</b> " +ec2label +"<br>" + 
    			"<b>Number of Slaves used for execution:</b> " + numberOfSlaves;
    	if (instanceType != null) 
    		text += "<br><b>Instance Type:</b> " + instanceType;
    	if (currentBidPrice != null) 
    		text += "<br><b>Spot bid price:</b> $" + currentBidPrice;
    	
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
