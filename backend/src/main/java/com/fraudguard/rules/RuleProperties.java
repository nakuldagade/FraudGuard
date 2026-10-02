package com.fraudguard.rules;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// Spring fills this object from the "fraudguard.rules" section of application.yml.
// If a value is missing there, the default written here is used.
// Tests can simply write "new RuleProperties()" to get the defaults, with no Spring needed.
@Component
@ConfigurationProperties(prefix = "fraudguard.rules")
@Getter
@Setter
public class RuleProperties {

    private HighVelocity highVelocity = new HighVelocity();
    private UnusualAmount unusualAmount = new UnusualAmount();
    private NewDeviceHighAmount newDeviceHighAmount = new NewDeviceHighAmount();
    private RapidCountryChange rapidCountryChange = new RapidCountryChange();
    private Decision decision = new Decision();

    @Getter
    @Setter
    public static class HighVelocity {
        private boolean enabled = true;
        private int maxPerFiveMinutes = 5;
        private int maxPerHour = 20;
        private int score = 40;
    }

    @Getter
    @Setter
    public static class UnusualAmount {
        private boolean enabled = true;
        private double minZScore = 3.0;
        private BigDecimal minAmount = new BigDecimal("1000");
        private int score = 50;
    }

    @Getter
    @Setter
    public static class NewDeviceHighAmount {
        private boolean enabled = true;
        private int minHistory = 5;
        private double amountMultiplier = 3.0;
        private int score = 30;
    }

    @Getter
    @Setter
    public static class RapidCountryChange {
        private boolean enabled = true;
        private long withinSeconds = 3600;
        private int score = 35;
    }

    @Getter
    @Setter
    public static class Decision {
        private int reviewAt = 40;
        private int blockAt = 80;
    }
}