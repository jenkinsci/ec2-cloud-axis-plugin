package hudson.plugins.ec2;

import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.GroupIdentifier;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.LaunchSpecification;
import com.amazonaws.services.ec2.model.RequestSpotInstancesRequest;
import com.amazonaws.services.ec2.model.RequestSpotInstancesResult;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.ec2.model.SpotPlacement;
import com.amazonaws.services.ec2.model.Tag;

public class SpotInstanceProvider {
	private String ami;
	private String description;
	private KeyPair keyPair;
	private String spotMaxBidPrice;
	private String bidType;
	private InstanceType type;
	private String zone;
	private String subnetId;
	private Set<String> securityGroupSet;
	private List<String> ec2SecurityGroups;
	private List<EC2Tag> tags;
	private Ec2AxisSlaveTemplate slaveTemplate;
	private EC2Cloud cloud;
	private PrintStream logger;
	
	public SpotInstanceProvider(
			KeyPair keyPair, 
			List<String> ec2SecurityGroups,
			Ec2AxisSlaveTemplate slaveTemplate,
			PrintStream logger) 
	{
		this.keyPair = keyPair;
		this.slaveTemplate = slaveTemplate;
		this.ec2SecurityGroups = ec2SecurityGroups;
		this.logger = logger;
		
		this.ami = slaveTemplate.ami;
		this.description = slaveTemplate.description;
		this.spotMaxBidPrice = slaveTemplate.getSpotMaxBidPrice();
		this.bidType = slaveTemplate.getBidType();
		this.type = slaveTemplate.type;
		this.zone = slaveTemplate.zone;
		this.subnetId = slaveTemplate.getSubnetId();
		this.securityGroupSet = slaveTemplate.getSecurityGroupSet();
		this.tags = slaveTemplate.getTags();
		this.cloud = slaveTemplate.getParent();
	}
	
	public List<EC2AbstractSlave> provisionMultiple(int numberOfInstancesToCreate)
					throws AmazonClientException, IOException {
		List<EC2AbstractSlave> spotSlaves = new ArrayList<EC2AbstractSlave>();

		try{
			logger.println("Launching " + ami + " for template " + description);

			RequestSpotInstancesRequest spotRequest = new RequestSpotInstancesRequest();

			if (spotMaxBidPrice == null){
				throw new AmazonClientException("Invalid Spot price specified: " + spotMaxBidPrice);
			}

			spotRequest.setSpotPrice(spotMaxBidPrice);
			spotRequest.setInstanceCount(numberOfInstancesToCreate);
			spotRequest.setType(bidType);

			LaunchSpecification launchSpecification = new LaunchSpecification();

			launchSpecification.setImageId(ami);
			launchSpecification.setInstanceType(type);

			if (StringUtils.isNotBlank(zone)) {
				SpotPlacement placement = new SpotPlacement(zone);
				launchSpecification.setPlacement(placement);
			}

			if (StringUtils.isNotBlank(subnetId)) {
				launchSpecification.setSubnetId(subnetId);

				/* If we have a subnet ID then we can only use VPC security groups */
				if (!securityGroupSet.isEmpty()) {
                    List<String> group_ids = ec2SecurityGroups;
                    ArrayList<GroupIdentifier> groups = new ArrayList<GroupIdentifier>();

                    for (String group_id : group_ids) {
                      GroupIdentifier group = new GroupIdentifier();
                      group.setGroupId(group_id);
                      groups.add(group);
                    }

                    if (!groups.isEmpty())
                        launchSpecification.setAllSecurityGroups(groups);
                }
			} else {
				/* No subnet: we can use standard security groups by name */
				if (securityGroupSet.size() > 0)
					launchSpecification.setSecurityGroups(securityGroupSet);
			}

			launchSpecification.setKeyName(keyPair.getKeyName());
			launchSpecification.setInstanceType(type.toString());

			spotRequest.setLaunchSpecification(launchSpecification);

			AmazonEC2 ec2 = cloud.connect();
			RequestSpotInstancesResult reqResult = ec2 .requestSpotInstances(spotRequest);

			List<SpotInstanceRequest> reqInstances = reqResult.getSpotInstanceRequests();
			if (reqInstances.size() <= 0){
				throw new AmazonClientException("No spot instances found");
			}

			HashSet<Tag> inst_tags = null;
			if (tags != null && !tags.isEmpty()) {
				inst_tags = new HashSet<Tag>();
				for(EC2Tag t : tags) {
					inst_tags.add(new Tag(t.getName(), t.getValue()));
				}
			}
			for (SpotInstanceRequest spotInstanceRequest : reqInstances) {
				if (spotInstanceRequest == null){
					logger.println("Spot instance request is null");
					continue;
				}
				/* Now that we have our Spot request, we can set tags on it */
				String spotInstanceRequestId = spotInstanceRequest.getSpotInstanceRequestId();
				if (inst_tags != null) {
					slaveTemplate.updateRemoteTags(ec2, inst_tags, spotInstanceRequestId);
					// That was a remote request - we should also update our local instance data.
					spotInstanceRequest.setTags(inst_tags);
				}
				
				logger.println("Spot instance id in provision: " + spotInstanceRequestId);
				String slaveName = description.replace(" ", "") + "@"+spotInstanceRequestId;
				EC2SpotSlave newSpotSlave = slaveTemplate.newSpotSlave(spotInstanceRequest, slaveName);
				
				spotSlaves.add(newSpotSlave);
				Hudson.getInstance().addNode(newSpotSlave);
			}
			
			monitorSpotRequestsAndMakeThemConnectToJenkins(ec2, reqInstances, spotSlaves);
			
			return spotSlaves;
		}  catch (FormException e) {
			throw new AssertionError();
		}
	}
	
	private void monitorSpotRequestsAndMakeThemConnectToJenkins(
			final AmazonEC2 ec2, 
			final List<SpotInstanceRequest> reqInstances, 
			final List<EC2AbstractSlave> spotSlaves) throws AmazonClientException, IOException 
	{
		SpotRequestConnectSupervisor.start(logger, reqInstances, spotSlaves, ec2, keyPair.getKeyMaterial().toCharArray(), slaveTemplate.getRemoteAdmin());
	}
}
