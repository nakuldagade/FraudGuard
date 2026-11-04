package com.fraudguard.security;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Everything under fraudguard.security.* in application.yml. Secrets come from environment variables
// (see .env.example); nothing secret is committed.
@ConfigurationProperties("fraudguard.security")
@Getter
@Setter
public class SecurityProperties {

    private int tokenTtlMinutes = 60;               // how long a login token lasts
    private String jwtSecret = "";                  // at least 32 bytes; blank is only allowed on the local profile
    private List<String> ingestApiKeys = new ArrayList<>();   // keys accepted on POST /api/transactions
    private String metricsUsername = "prometheus";  // HTTP Basic user for /actuator/**
    private String metricsPassword = "";
    private int loginAttemptsPerMinute = 5;         // per client IP

    // Users created at startup, but only if the users table is empty
    private String adminUsername = "";
    private String adminPassword = "";
    private String analystUsername = "";
    private String analystPassword = "";
    private String seedUsername = "";               // the demo labeller ("demo-oracle"), an ANALYST
    private String seedPassword = "";
}
