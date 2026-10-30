package dev.relayops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class RelayOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(RelayOpsApplication.class, args);
    }
}
