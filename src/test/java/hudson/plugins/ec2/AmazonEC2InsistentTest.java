package hudson.plugins.ec2;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.AllocateAddressResult;


public class AmazonEC2InsistentTest {

	private final AmazonEC2 ec2 = mock(AmazonEC2.class);
	private final EC2Logger logger = mock(EC2Logger.class);
	private final AmazonEC2 subject = AmazonEC2Insistent.wrap(ec2, logger , 0);
	
	@Rule 
	public ExpectedException expectedException= ExpectedException.none();
	
	@Test
	public void invokeMethodWithoutError() {
		subject.allocateAddress();
		verify(ec2).allocateAddress();
	}
	
	@Test
	public void retryWhenMethodThrow503ErrorFiveTimes() {
		AmazonServiceException ex = new AmazonServiceException("Service Error");
		ex.setStatusCode(503);
		AllocateAddressResult expectedResult = new AllocateAddressResult();
		when(ec2.allocateAddress())
			.thenThrow(ex)
			.thenThrow(ex)
			.thenThrow(ex)
			.thenThrow(ex)
			.thenThrow(ex)
			.thenReturn(expectedResult);
		
		AllocateAddressResult result = subject.allocateAddress();
		assertEquals(expectedResult, result);
	}
	

	@Test
	public void errorWhenMethodThrow404Error() {
		AmazonServiceException ex = new AmazonServiceException("Service Error");
		ex.setStatusCode(404);
		when(ec2.allocateAddress()).thenThrow(ex);
		
		expectedException.expect(AmazonServiceException.class);
		expectedException.expectMessage("Service Error");
		
		subject.allocateAddress();	
	}
	
	@Test
	public void errorWhenMethodThrowRuntimeException() {
		when(ec2.allocateAddress()).thenThrow(new RuntimeException());
		
		expectedException.expect(RuntimeException.class);
		subject.allocateAddress();	
	}

}
