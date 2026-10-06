package com.nexusops;

import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NexusOpsApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(NexusOpsApplication.class);
		if (isCliCommand(args)) { // platform account CLI: no web server, quiet logs, exit with the command's code
			application.setWebApplicationType(WebApplicationType.NONE);
			application.setBannerMode(Banner.Mode.OFF);
			application.setDefaultProperties(Map.of("logging.level.root", "WARN"));
			System.exit(SpringApplication.exit(application.run(args)));
		}
		application.run(args);
	}

	static boolean isCliCommand(String[] args) {
		return Arrays.stream(args).anyMatch(arg -> arg.startsWith("--nexusops.cli.command="));
	}
}
