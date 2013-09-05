package hudson.plugins.ec2;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;

import org.apache.commons.io.output.NullOutputStream;

import hudson.console.ConsoleNote;
import hudson.model.BuildListener;

@SuppressWarnings("serial")
public abstract class BuildStartEndListener implements BuildListener {
	private static PrintStream nullOutputStream = new PrintStream(new NullOutputStream());
	private static PrintWriter nullPrintWriter = new PrintWriter(nullOutputStream);
	public void hyperlink(String url, String text) throws IOException { }
	public void annotate(@SuppressWarnings("rawtypes") ConsoleNote ann) throws IOException {  }
	public PrintStream getLogger() { return nullOutputStream; }
	public PrintWriter fatalError(String format, Object... args) { return nullPrintWriter; }
	public PrintWriter fatalError(String msg) { return nullPrintWriter; }
	public PrintWriter error(String format, Object... args) { return nullPrintWriter; }
	public PrintWriter error(String msg) { return nullPrintWriter; }
}
