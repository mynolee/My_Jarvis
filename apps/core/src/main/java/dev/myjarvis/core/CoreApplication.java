package dev.myjarvis.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CoreApplication {
    public static void main(String[] args) { launch(args, System.getenv()); }

    static void launch(String[] args, java.util.Map<String, String> environment) {
        RestoreLaunchGuard.check(environment);
        SpringApplication.run(CoreApplication.class, args);
    }
}
