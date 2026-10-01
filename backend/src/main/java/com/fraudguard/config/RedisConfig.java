package com.fraudguard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class RedisConfig {

    // CONNECTION: Spring Boot builds it from spring.data.redis.host/port in application.yml.
    // SERIALIZATION: Boot's StringRedisTemplate reads and writes plain text, so everything in
    // Redis is human-readable in redis-cli (no Java-specific binary format).

    // Loads the Lua script from src/main/resources/redis/ once at startup.
    // Spring sends it to Redis by its hash (fast) and falls back to sending the full text if needed.
    @Bean
    DefaultRedisScript<String> recordTransactionScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/record_transaction.lua"));
        script.setResultType(String.class);   // the script returns one comma-separated string
        return script;
    }
}