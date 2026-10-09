package id.fayda.verification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Fayda-ID Check verification service: attempt lifecycle, decision composite and audit.
 * It never runs a model and never stores an image.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class VerificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VerificationServiceApplication.class, args);
    }
}
