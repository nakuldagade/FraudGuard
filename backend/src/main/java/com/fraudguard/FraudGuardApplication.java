package com.fraudguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // lets the OutboxRelay run on a timer
public class FraudGuardApplication {
    public static void main(String[] args) {
        SpringApplication.run(FraudGuardApplication.class, args);
    }
}