package hudson.plugins.ec2;

import hudson.model.TaskListener;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.util.StreamTaskListener;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.BlockDeviceMapping;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.DescribeSubnetsRequest;
import com.amazonaws.services.ec2.model.DescribeSubnetsResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.GroupIdentifier;
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.LaunchSpecification;
import com.amazonaws.services.ec2.model.ModifyInstanceAttributeRequest;
import com.amazonaws.services.ec2.model.Placement;
import com.amazonaws.services.ec2.model.RequestSpotInstancesRequest;
import com.amazonaws.services.ec2.model.RequestSpotInstancesResult;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.ec2.model.SpotPlacement;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.StartInstancesResult;
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
	
	public List<EC2AbstractSlave> provisionMultipleSlaves(StreamTaskListener listener, int numberOfInstancesToCreate) {
		try {
			if (spotConfig != null)
				return provisionMultipleSpot(listener, numberOfInstancesToCreate);
			
			return provisionOndemand(listener, numberOfInstancesToCreate);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private List<EC2AbstractSlave> provisionOndemand(TaskListener listener, int numberOfInstancesToCreate) 
			throws AmazonClientException, IOException {
        PrintStream logger = listener.getLogger();
        AmazonEC2 ec2 = getParent().connect();

        logger.println("Launching " + ami + " for template " + description);
        KeyPair keyPair = getKeyPair(ec2);
        List<EC2AbstractSlave> allocatedSlaves = (List<EC2AbstractSlave>) requestStoppedInstancesToAllocation(logger, ec2, keyPair);
        int instancesRemainingToCreate = numberOfInstancesToCreate - allocatedSlaves.size();
        if (instancesRemainingToCreate == 0)
        	return allocatedSlaves;
        
    	HashSet<Tag> inst_tags = new HashSet<Tag>();
    	if (getTags() != null && !getTags().isEmpty()) {
    		for(EC2Tag t : getTags()) {
    			inst_tags.add(new Tag(t.getName(), t.getValue()));
    		}
    	}
    	
    	RunInstancesRequest runInstanceRequest = createRunInstanceRequest(ec2, instancesRemainingToCreate, keyPair);
        List<Instance> createdInstances = ec2.runInstances(runInstanceRequest).getReservation().getInstances();
        logger.println("Sent instance creation request. Allocated instance count : " + createdInstances.size() );
        for (Instance inst : createdInstances) {
        	if (inst_tags.size() > 0) {
        		updateRemoteTags(ec2, inst_tags, inst.getInstanceId());
        		inst.setTags(inst_tags);
        	}
        	logger.println("Creating instance: "+inst.getInstanceId());
        	
        	EC2OndemandSlave newOndemandSlave = newOnDemandSlaveOrCry(inst);
        	logger.println("Slave "+ newOndemandSlave.getDisplayName() +"created for instance "+inst.getInstanceId());
        	allocatedSlaves.add(newOndemandSlave);
		}
        return allocatedSlaves;
    }

	private List<EC2AbstractSlave> requestStoppedInstancesToAllocation(
			PrintStream logger, AmazonEC2 ec2, KeyPair keyPair) {
		List<EC2AbstractSlave> slavesForExistingStoppedInstances = new LinkedList<EC2AbstractSlave>();
		
		List<Filter> describeInstanceFilters = new ArrayList<Filter>();
		describeInstanceFilters.add(new Filter("image-id").withValues(ami));
		if (StringUtils.isNotBlank(zone)) {
		    describeInstanceFilters.add(new Filter("availability-zone").withValues(getZone()));
		}

		if (StringUtils.isNotBlank(getSubnetId())) {
		   describeInstanceFilters.add(new Filter("subnet-id").withValues(getSubnetId()));

		   /* If we have a subnet ID then we can only use VPC security groups */
		   if (!getSecurityGroupSet().isEmpty()) {
		      List<String> group_ids = getEc2SecurityGroups(ec2);

		      if (!group_ids.isEmpty()) {
		         describeInstanceFilters.add(new Filter("instance.group-id").withValues(group_ids));
		      }
		   }
		} else {
		   /* No subnet: we can use standard security groups by name */
			if (getSecurityGroupSet().size() > 0)
				describeInstanceFilters.add(new Filter("group-name").withValues(getSecurityGroupSet()));
		}
		
		describeInstanceFilters.add(new Filter("key-name").withValues(keyPair.getKeyName()));
		describeInstanceFilters.add(new Filter("instance-type").withValues(type.toString()));

		if (getTags() != null && !getTags().isEmpty()) {
		    for(EC2Tag t : getTags()) {
		        describeInstanceFilters.add(new Filter("tag:"+t.getName()).withValues(t.getValue()));
		    }
		}
		
		DescribeInstancesRequest diRequest = new DescribeInstancesRequest();
		describeInstanceFilters.add(new Filter("instance-state-name").withValues(InstanceStateName.Stopped.toString(), 
				InstanceStateName.Stopping.toString()));
		diRequest.setFilters(describeInstanceFilters);
		logger.println("Looking for existing instances: "+diRequest);

		DescribeInstancesResult diResult = ec2.describeInstances(diRequest);
		if (diResult.getReservations().size() == 0) 
			return slavesForExistingStoppedInstances;
		List<Instance> instances = diResult.getReservations().get(0).getInstances();

		//TODO: multiple startRequests could be combined 
		for (Instance existingInstance : instances) {
			logger.println("Found existing stopped instance: "+existingInstance);
			List<String> instancesNames = new ArrayList<String>();
			instancesNames.add(existingInstance.getInstanceId());
			StartInstancesRequest siRequest = new StartInstancesRequest(instancesNames);
			StartInstancesResult siResult = ec2.startInstances(siRequest);
			logger.println("Starting existing instance: "+existingInstance+ " result:"+siResult);

			List<Node> nodes = Hudson.getInstance().getNodes();
			for (int i = 0, len = nodes.size(); i < len; i++) {
				if (!(nodes.get(i) instanceof EC2AbstractSlave))
					continue;
				EC2AbstractSlave ec2Node = (EC2AbstractSlave) nodes.get(i);
				if (ec2Node.getInstanceId().equals(existingInstance.getInstanceId())) {
			        logger.println("Found existing corresponding: "+ec2Node);
			        slavesForExistingStoppedInstances.add(ec2Node);
				}
			}
			
			// Existing slave not found 
			logger.println("Creating new slave for existing instance: "+existingInstance);
			EC2OndemandSlave ondemandSlave = newOnDemandSlaveOrCry(existingInstance);
			slavesForExistingStoppedInstances.add(ondemandSlave);
		}
		
		return slavesForExistingStoppedInstances;
	}

	private EC2OndemandSlave newOnDemandSlaveOrCry(Instance existingInstance) {
		EC2OndemandSlave ondemandSlave;
		try {
			ondemandSlave = newOndemandSlave(existingInstance);
		} catch (FormException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return ondemandSlave;
	}

	private RunInstancesRequest createRunInstanceRequest(AmazonEC2 ec2, int numberOfInstancesToCreate, KeyPair keyPair) 
	{
		RunInstancesRequest runInstanceRequest = new RunInstancesRequest(ami, numberOfInstancesToCreate, numberOfInstancesToCreate);
		setupDeviceMapping(runInstanceRequest);
		if (StringUtils.isNotBlank(zone)) {
			Placement placement = new Placement(getZone());
			runInstanceRequest.setPlacement(placement);
		}
		if (StringUtils.isNotBlank(getSubnetId())) {
		   runInstanceRequest.setSubnetId(getSubnetId());

		   /* If we have a subnet ID then we can only use VPC security groups */
		   if (!getSecurityGroupSet().isEmpty()) {
		      List<String> group_ids = getEc2SecurityGroups(ec2);

		      if (!group_ids.isEmpty()) {
		         runInstanceRequest.setSecurityGroupIds(group_ids);
		      }
		   }
		} else {
		   /* No subnet: we can use standard security groups by name */
			runInstanceRequest.setSecurityGroups(getSecurityGroupSet());
		}

		String userDataString = Base64.encodeBase64String(userData.getBytes());
		runInstanceRequest.setUserData(userDataString);
		runInstanceRequest.setKeyName(keyPair.getKeyName());
		runInstanceRequest.setInstanceType(type.toString());
		
		return runInstanceRequest;
	}
	
	private void setupDeviceMapping(RunInstancesRequest riRequest) {
        final List<BlockDeviceMapping> oldDeviceMapping = getAmiBlockDeviceMappings();

        final Set<String> occupiedDevices = new HashSet<String>();
        for (final BlockDeviceMapping mapping: oldDeviceMapping ) {

            occupiedDevices.add(mapping.getDeviceName());
        }

        final List<String> available = new ArrayList<String>(Arrays.asList(
                "ephemeral0", "ephemeral1", "ephemeral2", "ephemeral3"
        ));

        final List<BlockDeviceMapping> newDeviceMapping = new ArrayList<BlockDeviceMapping>(4);
        for (char suffix = 'b'; suffix <= 'z' && !available.isEmpty(); suffix++) {

            final String deviceName = String.format("/dev/xvd%s", suffix);

            if (occupiedDevices.contains(deviceName)) continue;

            final BlockDeviceMapping newMapping = new BlockDeviceMapping()
                    .withDeviceName(deviceName)
                    .withVirtualName(available.get(0))
            ;

            newDeviceMapping.add(newMapping);
            available.remove(0);
        }

        riRequest.withBlockDeviceMappings(newDeviceMapping);
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
    private void updateRemoteTags(AmazonEC2 ec2, Collection<Tag> inst_tags, String... params) {
    	CreateTagsRequest tag_request = new CreateTagsRequest();
        tag_request.withResources(params).setTags(inst_tags);
        ec2.createTags(tag_request);
    }
    
    private List<BlockDeviceMapping> getAmiBlockDeviceMappings() {

        /*
         * AmazonEC2#describeImageAttribute does not work due to a bug
         * https://forums.aws.amazon.com/message.jspa?messageID=231972
         */
        for (final Image image: getParent().connect().describeImages().getImages()) {

            if (ami.equals(image.getImageId())) {

                return image.getBlockDeviceMappings();
            }
        }

        throw new AmazonClientException("Unable to get AMI device mapping for " + ami);
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
	
	private List<EC2AbstractSlave> provisionMultipleSpot(StreamTaskListener listener, int numberOfInstancesToCreate) throws AmazonClientException, IOException {
		PrintStream logger = listener.getLogger();
		AmazonEC2 ec2 = getParent().connect();
		List<EC2AbstractSlave> spotSlaves = new ArrayList<EC2AbstractSlave>();

		try{
			logger.println("Launching " + ami + " for template " + description);
			KeyPair keyPair = getKeyPair(ec2);

			RequestSpotInstancesRequest spotRequest = new RequestSpotInstancesRequest();

			if (getSpotMaxBidPrice() == null){
				throw new AmazonClientException("Invalid Spot price specified: " + getSpotMaxBidPrice());
			}

			spotRequest.setSpotPrice(getSpotMaxBidPrice());
			spotRequest.setInstanceCount(numberOfInstancesToCreate);
			spotRequest.setType(getBidType());

			LaunchSpecification launchSpecification = new LaunchSpecification();

			launchSpecification.setImageId(ami);
			launchSpecification.setInstanceType(type);

			if (StringUtils.isNotBlank(getZone())) {
				SpotPlacement placement = new SpotPlacement(getZone());
				launchSpecification.setPlacement(placement);
			}

			if (StringUtils.isNotBlank(getSubnetId())) {
				launchSpecification.setSubnetId(getSubnetId());

				/* If we have a subnet ID then we can only use VPC security groups */
				if (!getSecurityGroupSet().isEmpty()) {
                    List<String> group_ids = getEc2SecurityGroups(ec2);
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
				if (getSecurityGroupSet().size() > 0)
					launchSpecification.setSecurityGroups(getSecurityGroupSet());
			}

			// The slave must know the Jenkins server to register with as well
			// as the name of the node in Jenkins it should register as. The only
			// way to give information to the Spot slaves is through the ec2 user data
			String jenkinsUrl = Hudson.getInstance().getRootUrl();
			
			// We must provide a unique node name for the slave to connect to Jenkins.
			// We don't have the EC2 generated instance ID, or the Spot request ID
			// until after the instance is requested, which is then too late to set the
			// user-data for the request. Instead we generate a unique name from UUID
			// so that the slave has a unique name within Jenkins to register to.
			String slaveName = UUID.randomUUID().toString();
			String newUserData = "JENKINS_URL=" + jenkinsUrl +
					"&SLAVE_NAME=" + slaveName +
					"&USER_DATA=" + Base64.encodeBase64String(userData.getBytes());

			String userDataString = Base64.encodeBase64String(newUserData.getBytes());
			launchSpecification.setUserData(userDataString);
			launchSpecification.setKeyName(keyPair.getKeyName());
			launchSpecification.setInstanceType(type.toString());

			
			spotRequest.setLaunchSpecification(launchSpecification);

			// Make the request for a new Spot instance
			RequestSpotInstancesResult reqResult = ec2.requestSpotInstances(spotRequest);

			List<SpotInstanceRequest> reqInstances = reqResult.getSpotInstanceRequests();
			if (reqInstances.size() <= 0){
				throw new AmazonClientException("No spot instances found");
			}

			HashSet<Tag> inst_tags = null;
			if (getTags() != null && !getTags().isEmpty()) {
				inst_tags = new HashSet<Tag>();
				for(EC2Tag t : getTags()) {
					inst_tags.add(new Tag(t.getName(), t.getValue()));
				}
			}
			for (SpotInstanceRequest spotInstanceRequest : reqInstances) {
				if (spotInstanceRequest == null){
					logger.println("Spot instance request is null");
					continue;
				}
				/* Now that we have our Spot request, we can set tags on it */
				if (inst_tags != null) {
					updateRemoteTags(ec2, inst_tags, spotInstanceRequest.getSpotInstanceRequestId());
					// That was a remote request - we should also update our local instance data.
					spotInstanceRequest.setTags(inst_tags);
					ModifyInstanceAttributeRequest modifyInstanceAttributeRequest = 
							new ModifyInstanceAttributeRequest(); 
					modifyInstanceAttributeRequest.setUserData("");
					ec2.modifyInstanceAttribute(modifyInstanceAttributeRequest);
				}
				
				logger.println("Spot instance id in provision: " + spotInstanceRequest.getSpotInstanceRequestId());
				EC2SpotSlave newSpotSlave = newSpotSlave(spotInstanceRequest, slaveName);
				
				spotSlaves.add(newSpotSlave);
			}
			
			return spotSlaves;

		}  catch (FormException e) {
			throw new AssertionError(); // we should have discovered all configuration issues upfront
		}
	}
}
