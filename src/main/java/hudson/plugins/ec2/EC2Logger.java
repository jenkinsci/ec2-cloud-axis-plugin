package hudson.plugins.ec2;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class EC2Logger {

	private PrintStream printStream;

	public EC2Logger(PrintStream printStream) {
		this.printStream = printStream;
	}
	
	public void println(String string) {
		printStream.println(new SimpleDateFormat().format(new Date()) + " : "+string);
	}

	public void print(String string) {
		printStream.print(string);
	}

	public void printStackTrace(Exception e) {
		e.printStackTrace(printStream);
	}

}
