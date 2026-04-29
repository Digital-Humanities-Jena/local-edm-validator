package eu.europeana.metis.sandbox;

import eu.europeana.metis.sandbox.cli.SandboxCli;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring boot application entry point.
 */
@SpringBootApplication
public class SandboxApplication {

  public static void main(String[] args) {
    if (args.length > 0 && "--cli".equals(args[0])) {
      int exitCode = new SandboxCli(System.in, System.out, System.err).run(Arrays.copyOfRange(args, 1, args.length));
      if (exitCode != 0) {
        System.exit(exitCode);
      }
      return;
    }
    SpringApplication.run(SandboxApplication.class, args);
  }

}
