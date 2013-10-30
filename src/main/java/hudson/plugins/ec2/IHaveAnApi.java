package hudson.plugins.ec2;

import hudson.model.Api;
import hudson.model.ModelObject;

import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean
public class IHaveAnApi implements ModelObject {

	@Override
	public String getDisplayName() {
		return "IHaveAnApi";
	}

	@Exported
	public String getSomethig() {
		return "getSomethig";
	}
	
	@Exported
	public String getData(String data) {
		return data;
	}
	
	public Api getApi() {
        return new Api(this);
    }
}
