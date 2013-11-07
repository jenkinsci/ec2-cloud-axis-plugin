package hudson.plugins.ec2;

import hudson.model.TaskListener;
import hudson.model.Descriptor.FormException;
import hudson.slaves.NodeProperty;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryRequest;
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryResult;
import com.amazonaws.services.ec2.model.DescribeSubnetsRequest;
import com.amazonaws.services.ec2.model.DescribeSubnetsResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.ec2.model.SpotPrice;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.ec2.model.Tag;


public class Ec2AxisSlaveTemplate extends SlaveTemplate {

	private transient String instanceLabel;

	public Ec2AxisSlaveTemplate(SlaveTemplate toDecorate) {
		super(
			 toDecorate.ami, 
			 toDecorate.zone, 
			 toDecorate.spotConfig,
			 toDecorate.securityGroups, 
			 toDecorate.remoteFS, 
			 toDecorate.sshPort, 
			 toDecorate.type, 
			 toDecorate.getLabelString(),
			 toDecorate.mode, 
			 toDecorate.description, 
			 toDecorate.initScript, 
			 toDecorate.userData, 
			 toDecorate.numExecutors, 
			 toDecorate.remoteAdmin, 
			 toDecorate.rootCommandPrefix, 
			 toDecorate.jvmopts, 
			 toDecorate.stopOnTerminate, 
			 toDecorate.subnetId, 
			 toDecorate.getTags(), 
			 toDecorate.idleTerminationMinutes, 
			 toDecorate.usePrivateDnsName, 
			 toDecorate.getInstanceCapStr(),
			 toDecorate.iamInstanceProfile,
			 toDecorate.useEphemeralDevices,
			 toDecorate.getLaunchTimeoutStr());
	}
	
	public List<EC2AbstractSlave> provisionMultipleSlaves(EC2Logger logger, int numberOfInstancesToCreate) {
		try {
			AmazonEC2 ec2 = getParent().connect();
			KeyPair keyPair = getKeyPair(ec2);
			List<String> ec2SecurityGroups = getEc2SecurityGroups(ec2);
			
			if (spotConfig != null) {
				SpotInstanceProvider spotInstanceFactory = new SpotInstanceProvider(keyPair, ec2SecurityGroups, this, logger);
				return spotInstanceFactory.provisionMultiple(numberOfInstancesToCreate);
			}
			
			OnDemandInstanceProvider reservedInstanceProvider = new OnDemandInstanceProvider(keyPair, ec2SecurityGroups, this, logger);
			return reservedInstanceProvider.provisionMultiple(numberOfInstancesToCreate);
			
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}


	private KeyPair getKeyPair(AmazonEC2 ec2) throws IOException, AmazonClientException{
    	EC2AxisCloud ec2AxisCloud = (EC2AxisCloud)getParent();
    	KeyPair keyPair = ec2AxisCloud.getKeyPair(ec2);
    	
    	if(keyPair==null) {
        	throw new AmazonClientException("No matching keypair found on EC2. Is the EC2 private key a valid one?");
    	}
    	return keyPair;
    }
    
    /**
     * Get a list of security group ids for the slave
     */
    private List<String> getEc2SecurityGroups(AmazonEC2 ec2) throws AmazonClientException{
    	List<String> group_ids = new ArrayList<String>();

		DescribeSecurityGroupsRequest group_req = new DescribeSecurityGroupsRequest();
		group_req.withFilters(new Filter("group-name").withValues(getSecurityGroupSet()));
		DescribeSecurityGroupsResult group_result = ec2.describeSecurityGroups(group_req);

		for (SecurityGroup group : group_result.getSecurityGroups()) {
			if (group.getVpcId() != null && !group.getVpcId().isEmpty()) {
				List<Filter> filters = new ArrayList<Filter>();
				filters.add(new Filter("vpc-id").withValues(group.getVpcId()));
				filters.add(new Filter("state").withValues("available"));
				filters.add(new Filter("subnet-id").withValues(getSubnetId()));

				DescribeSubnetsRequest subnet_req = new DescribeSubnetsRequest();
				subnet_req.withFilters(filters);
				DescribeSubnetsResult subnet_result = ec2.describeSubnets(subnet_req);

				List<Subnet> subnets = subnet_result.getSubnets();
				if(subnets != null && !subnets.isEmpty()) {
					group_ids.add(group.getGroupId());
				}
			}
		}

		if (getSecurityGroupSet().size() != group_ids.size()) {
			throw new AmazonClientException( "Security groups must all be VPC security groups to work in a VPC context" );
		}

		return group_ids;
    }
    
    /**
     * Update the tags stored in EC2 with the specified information
     */
    void updateRemoteTags(AmazonEC2 ec2, Collection<Tag> inst_tags, String... params) {
    	CreateTagsRequest tag_request = new CreateTagsRequest();
        tag_request.withResources(params).setTags(inst_tags);
        ec2.createTags(tag_request);
    }
    
	public void setInstanceLabel(String displayName) {
		this.instanceLabel = displayName;
	}

	@Override
	public EC2AbstractSlave provision(TaskListener listener) throws AmazonClientException, IOException {
		EC2AbstractSlave provisionedSlave = super.provision(listener);
		if (instanceLabel != null)
			provisionedSlave.setLabelString(instanceLabel);
		return provisionedSlave;
	}
	
	@Override
	public EC2SpotSlave newSpotSlave(SpotInstanceRequest sir, String name) throws FormException, IOException {
		EC2SpotSlave newSpotSlave = super.newSpotSlave(sir, name);
		return newSpotSlave;
	}
	
	@Override
	public EC2OndemandSlave newOndemandSlave(Instance inst) throws FormException, IOException {
		EC2OndemandSlave ec2OndemandSlave = new EC2OndemandSlave(description.replace(" ", "") + "@" + inst.getInstanceId() , inst.getInstanceId() , 
				description, remoteFS, getSshPort(), getNumExecutors(), instanceLabel, mode, 
				initScript, Collections.<NodeProperty<?>>emptyList(), remoteAdmin, rootCommandPrefix, jvmopts, 
				stopOnTerminate, idleTerminationMinutes, inst.getPublicDnsName(), inst.getPrivateDnsName(),
				EC2Tag.fromAmazonTags(inst.getTags()), parent.name, usePrivateDnsName, launchTimeout);
		return ec2OndemandSlave;
	}

	public String getCurrentSpotPrice() {
		String cp = "";

		String region = ((AmazonEC2Cloud)getParent()).getRegion();
		AmazonEC2 ec2 = EC2Cloud.connect(getParent().getAccessId(), getParent().getSecretKey(), AmazonEC2Cloud.getEc2EndpointUrl(region));

		if(ec2==null) 
			return null;
		
		try {
			DescribeSpotPriceHistoryRequest request = new DescribeSpotPriceHistoryRequest();
			InstanceType ec2Type = null;
			for(InstanceType it : InstanceType.values()){
				if (it.name().toString().equals(type.name())){
					ec2Type = it;
					break;
				}
			}
			if(ec2Type == null){
				return null;				}

			Collection<String> instanceType = new ArrayList<String>();
			instanceType.add(ec2Type.toString());
			request.setInstanceTypes(instanceType);
			request.setStartTime(new Date());

			DescribeSpotPriceHistoryResult result = ec2.describeSpotPriceHistory(request);

			if(!result.getSpotPriceHistory().isEmpty()){
				SpotPrice currentPrice = result.getSpotPriceHistory().get(0);

				cp = currentPrice.getSpotPrice();
			}
			return cp;
		} catch (AmazonClientException e) {
			return null;
		}
	}
}
