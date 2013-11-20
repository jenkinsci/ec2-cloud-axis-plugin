package hudson.plugins.ec2;

import hudson.model.Hudson;
import hudson.model.Node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.BlockDeviceMapping;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.Placement;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.StartInstancesResult;
import com.amazonaws.services.ec2.model.Tag;

public class OnDemandInstanceProvider {
	private String ami;
	private String description;
	private KeyPair keyPair;
	private InstanceType type;
	private String zone;
	private String subnetId;
	private Set<String> securityGroupSet;
	private List<String> ec2SecurityGroups;
	private List<EC2Tag> tags;
	private Ec2AxisSlaveTemplate slaveTemplate;
	private EC2Cloud cloud;
	private String userData;
	private EC2Logger logger;
	
	public OnDemandInstanceProvider(
			KeyPair keyPair, 
			List<String> ec2SecurityGroups,
			Ec2AxisSlaveTemplate slaveTemplate,
			EC2Logger logger) 
	{
		this.keyPair = keyPair;
		this.ec2SecurityGroups = ec2SecurityGroups;
		this.slaveTemplate = slaveTemplate;
		this.logger = logger;
		
		ami = slaveTemplate.ami;
		description = slaveTemplate.description;
		type = slaveTemplate.type;
		zone = slaveTemplate.zone;
		subnetId = slaveTemplate.subnetId;
		securityGroupSet = slaveTemplate.getSecurityGroupSet();
		tags = slaveTemplate.getTags();
		cloud = slaveTemplate.getParent();
		userData = slaveTemplate.userData;
	}
	
	public List<EC2AbstractSlave> provisionMultiple(int numberOfInstancesToCreate) 
			throws AmazonClientException, IOException {
		
        AmazonEC2 ec2 = cloud.connect();

        logger.println("Launching " + ami + " for template " + description);
        List<EC2AbstractSlave> allocatedSlaves = (List<EC2AbstractSlave>) requestStoppedInstancesToAllocation(ec2, keyPair);
        int instancesRemainingToCreate = numberOfInstancesToCreate - allocatedSlaves.size();
        if (instancesRemainingToCreate == 0)
        	return allocatedSlaves;
        
    	HashSet<Tag> inst_tags = new HashSet<Tag>();
    	if (tags != null && !tags.isEmpty()) {
    		for(EC2Tag t : tags) {
    			inst_tags.add(new Tag(t.getName(), t.getValue()));
    		}
    	}
    	
    	RunInstancesRequest runInstanceRequest = createRunInstanceRequest(ec2, instancesRemainingToCreate, keyPair);
        List<Instance> createdInstances = ec2.runInstances(runInstanceRequest).getReservation().getInstances();
        logger.println("Sent instance creation request. Allocated instance count : " + createdInstances.size() );
        for (Instance inst : createdInstances) {
        	if (inst_tags.size() > 0) {
        		slaveTemplate.updateRemoteTags(ec2, inst_tags, inst.getInstanceId());
        		inst.setTags(inst_tags);
        	}
        	logger.println("Creating instance: "+inst.getInstanceId());
        	
        	EC2OndemandSlave newOndemandSlave = newOnDemandSlaveOrCry(inst);
        	logger.println("Slave "+ newOndemandSlave.getDisplayName() +" created for instance "+inst.getInstanceId());
        	allocatedSlaves.add(newOndemandSlave);
		}
        
        for (EC2AbstractSlave ec2Slave : allocatedSlaves) {
        	Hudson.getInstance().addNode(ec2Slave);
		}
        
        OnDemandSlaveLauncher.launchSlaves(allocatedSlaves, logger);
        return allocatedSlaves;
    }
	
	private List<EC2AbstractSlave> requestStoppedInstancesToAllocation(
			AmazonEC2 ec2, KeyPair keyPair) {
		List<EC2AbstractSlave> slavesForExistingStoppedInstances = new LinkedList<EC2AbstractSlave>();
		
		List<Filter> describeInstanceFilters = new ArrayList<Filter>();
		describeInstanceFilters.add(new Filter("image-id").withValues(ami));
		if (StringUtils.isNotBlank(zone)) {
		    describeInstanceFilters.add(new Filter("availability-zone").withValues(zone));
		}

		if (StringUtils.isNotBlank(subnetId)) {
		   describeInstanceFilters.add(new Filter("subnet-id").withValues(subnetId));

		   /* If we have a subnet ID then we can only use VPC security groups */
		   if (!securityGroupSet.isEmpty()) {
		      List<String> group_ids = ec2SecurityGroups;

		      if (!group_ids.isEmpty()) {
		         describeInstanceFilters.add(new Filter("instance.group-id").withValues(group_ids));
		      }
		   }
		} else {
		   /* No subnet: we can use standard security groups by name */
			if (securityGroupSet.size() > 0)
				describeInstanceFilters.add(new Filter("group-name").withValues(securityGroupSet));
		}
		
		describeInstanceFilters.add(new Filter("key-name").withValues(keyPair.getKeyName()));
		describeInstanceFilters.add(new Filter("instance-type").withValues(type.toString()));

		if (tags != null && !tags.isEmpty()) {
		    for(EC2Tag t : tags) {
		        describeInstanceFilters.add(new Filter("tag:"+t.getName()).withValues(t.getValue()));
		    }
		}
		
		DescribeInstancesRequest diRequest = new DescribeInstancesRequest();
		describeInstanceFilters.add(new Filter("instance-state-name").withValues(InstanceStateName.Stopped.toString(), 
				InstanceStateName.Stopping.toString()));
		diRequest.setFilters(describeInstanceFilters);

		DescribeInstancesResult diResult = ec2.describeInstances(diRequest);
		if (diResult.getReservations().size() == 0) 
			return slavesForExistingStoppedInstances;
		List<Instance> instances = diResult.getReservations().get(0).getInstances();

		for (Instance existingInstance : instances) {
			logger.println("Found existing stopped instance: " + existingInstance);
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
			ondemandSlave = slaveTemplate.newOndemandSlave(existingInstance);
		} catch (Exception e) {
			throw new RuntimeException(e);
		} 
		return ondemandSlave;
	}

	private RunInstancesRequest createRunInstanceRequest(AmazonEC2 ec2, int numberOfInstancesToCreate, KeyPair keyPair) 
	{
		RunInstancesRequest runInstanceRequest = new RunInstancesRequest(ami, numberOfInstancesToCreate, numberOfInstancesToCreate);
		setupDeviceMapping(runInstanceRequest);
		if (StringUtils.isNotBlank(zone)) {
			Placement placement = new Placement(zone);
			runInstanceRequest.setPlacement(placement);
		}
		if (StringUtils.isNotBlank(subnetId)) {
		   runInstanceRequest.setSubnetId(subnetId);

		   if (!securityGroupSet.isEmpty()) {
		      List<String> group_ids = ec2SecurityGroups;

		      if (!group_ids.isEmpty()) {
		         runInstanceRequest.setSecurityGroupIds(group_ids);
		      }
		   }
		} else {
			runInstanceRequest.setSecurityGroups(securityGroupSet);
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
	
	private List<BlockDeviceMapping> getAmiBlockDeviceMappings() {
        for (final Image image: cloud.connect().describeImages().getImages()) {
            if (ami.equals(image.getImageId())) {
                return image.getBlockDeviceMappings();
            }
        }
        throw new AmazonClientException("Unable to get AMI device mapping for " + ami);
    }
}
