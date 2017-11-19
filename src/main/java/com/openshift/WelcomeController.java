package com.openshift;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class WelcomeController {

	// inject via application.properties
	@Value("${welcome.message:test}")
	private String message = "Hello ING Again from OpenShift";

	@RequestMapping("/")
	public String welcome(Map<String, Object> model) {
		model.put("message", this.message);
                
                String myName = System.getenv("MY_DEPLOYMENT_NAME");
                if (myName != null && myName.indexOf("blue") >= 0)
                  model.put("currentDeployment","blue");
                else
                  model.put("currentDeployment","green");
		return "index";
	}

}
