package com.pdfgen.bootstrap;

import com.pdfgen.adapters.cli.CliCommands;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

/**
 * Entry point. {@code serve} starts the local web viewer as a desktop app ({@link ServeLauncher}, ADR-0003,
 * ADR-0021); anything else runs the CLI without a web server and exits with the command's status code.
 */
@SpringBootApplication(scanBasePackages = "com.pdfgen")
public class PdfGeneratorApplication {

    public static void main(String[] args) {
        boolean serve = args.length > 0 && args[0].equals("serve");
        if (serve) {
            int code = ServeLauncher.launch(args);
            if (code != 0) {
                System.exit(code);
            }
            return; // the web server threads keep the application running until Quit
        }
        SpringApplication app = new SpringApplication(PdfGeneratorApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of(
                "spring.main.banner-mode", "off",
                "spring.main.log-startup-info", "false",
                "logging.level.root", "WARN"));
        ConfigurableApplicationContext context = app.run(args);
        int exitCode = context.getBean(CliCommands.class).execute(args);
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }
}
