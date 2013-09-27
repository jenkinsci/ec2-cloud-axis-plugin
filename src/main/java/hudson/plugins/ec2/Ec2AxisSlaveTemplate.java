package hudson.plugins.ec2;

import hudson.model.TaskListener;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.slaves.NodeProperty;
import hudson.util.StreamTaskListener;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

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
import com.amazonaws.services.ec2.model.EbsBlockDevice;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.Placement;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.StartInstancesResult;
import com.amazonaws.services.ec2.model.Subnet;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.VolumeType;


public class Ec2AxisSlaveTemplate extends SlaveTemplate {

	private transient String instanceLabel;

	public Ec2AxisSlaveTemplate(SlaveTemplate toDecorate) {
		super(
			 toDecorate.ami, 
			 toDecorate.zone, 
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
			 toDecorate.getInstanceCapStr());
	}
	
	public List<EC2Slave> provisionMultipleSlaves(StreamTaskListener listener, int numberOfInstancesToCreate) {
		try {
			return provisionOndemand(listener, numberOfInstancesToCreate);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private List<EC2Slave> provisionOndemand(TaskListener listener, int numberOfInstancesToCreate) 
			throws AmazonClientException, IOException {
        PrintStream logger = listener.getLogger();
        AmazonEC2 ec2 = getParent().connect();

        logger.println("Launching " + ami + " for template " + description);
        KeyPair keyPair = getKeyPair(ec2);
        List<EC2Slave> allocatedSlaves = getSlavesForExistingStoppedInstances(logger, ec2, keyPair);

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
        	logger.println("Creating instance: "+inst);
        	
        	EC2Slave newOndemandSlave = newOndemandSlave(inst);
        	logger.println("Slave "+ newOndemandSlave.getDisplayName() +"created for instance "+inst);
        	allocatedSlaves.add(newOndemandSlave);
		}
        return allocatedSlaves;
    }

	private List<EC2Slave> getSlavesForExistingStoppedInstances(
			PrintStream logger, AmazonEC2 ec2, KeyPair keyPair) {
		List<EC2Slave> slavesForExistingStoppedInstances = new LinkedList<EC2Slave>();
		
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
				if (!(nodes.get(i) instanceof EC2Slave))
					continue;
				EC2Slave ec2Node = (EC2Slave) nodes.get(i);
				if (ec2Node.getInstanceId().equals(existingInstance.getInstanceId())) {
			        logger.println("Found existing corresponding: "+ec2Node);
			        slavesForExistingStoppedInstances.add(ec2Node);
				}
			}
			
			// Existing slave not found 
			logger.println("Creating new slave for existing instance: "+existingInstance);
			slavesForExistingStoppedInstances.add(newOndemandSlave(existingInstance));
		}
		
		return slavesForExistingStoppedInstances;
	}
	
    private EC2Slave newOndemandSlave(Instance inst) {
        try {
			return new EC2Slave(
					inst.getInstanceId(), 
					description, 
					remoteFS, 
					getSshPort(), 
					getNumExecutors(), 
					mode,
					labels,
					initScript,
					Collections.<NodeProperty<?>>emptyList(),
					remoteAdmin, 
					rootCommandPrefix, 
					jvmopts, 
					stopOnTerminate, 
					idleTerminationMinutes, 
					inst.getPublicDnsName(), 
					inst.getPrivateDnsName(), 
					EC2Tag.fromAmazonTags(inst.getTags()), 
					usePrivateDnsName);
		} catch (FormException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
    }

	private RunInstancesRequest createRunInstanceRequest(AmazonEC2 ec2,
			int numberOfInstancesToCreate, KeyPair keyPair) {
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
		
		BlockDeviceMapping b = new BlockDeviceMapping();
		EbsBlockDevice ebs = new EbsBlockDevice();
		b.setDeviceName("/dev/sda1");
		ebs.setVolumeType(VolumeType.Io1);
		ebs.setIops(4000);
		b.setEbs(ebs);
		
		runInstanceRequest.withBlockDeviceMappings(b);
		
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
	public EC2Slave provision(TaskListener listener) throws AmazonClientException, IOException {
		EC2Slave provisionedSlave = super.provision(listener);
		if (instanceLabel != null)
			provisionedSlave.setLabelString(instanceLabel);
		return provisionedSlave;
	}
}
