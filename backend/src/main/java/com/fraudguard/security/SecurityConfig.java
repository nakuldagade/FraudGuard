package com.fraudguard.security;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;

// Three filter chains, tried in order. The first whose pattern matches the URL handles the request.
//   1. /actuator/**  HTTP Basic, role METRICS (Prometheus). Health probes are public.
//   2. /api/**       stateless JWT (login token) or, for ingestion only, an API key.
//   3. everything else: closed.
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
@Slf4j
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();     // slow on purpose, salted: a stolen database can't be reversed cheaply
    }

    @Bean
    JwtDecoder jwtDecoder(JwtService jwtService) {
        return jwtService.decoder();
    }

    // The one account allowed to read /actuator/** (besides the public health probes). Defined here as the
    // only UserDetailsService, which also stops Spring Boot from creating its default random-password user.
    @Bean
    UserDetailsService metricsUsers(SecurityProperties properties, PasswordEncoder encoder) {
        InMemoryUserDetailsManager users = new InMemoryUserDetailsManager();
        if (properties.getMetricsPassword().isBlank()) {
            log.warn("FRAUDGUARD_METRICS_PASSWORD is not set: /actuator/** (except health) is closed to everyone, "
                    + "so Prometheus cannot scrape.");
        } else {
            users.createUser(User.withUsername(properties.getMetricsUsername())
                    .password(encoder.encode(properties.getMetricsPassword()))
                    .roles("METRICS").build());
        }
        return users;
    }

    @Bean
    @Order(1)
    SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint();
        entryPoint.setRealmName("fraudguard-metrics");
        http.securityMatcher("/actuator/**")
                .csrf(AbstractHttpConfigurer::disable)     // no browser session or cookie here, so nothing for CSRF to abuse
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().hasRole("METRICS"));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, SecurityProperties properties,
                                 ApiSecurityErrorHandler errors) throws Exception {
        http.securityMatcher("/api/**")
                // CSRF protects cookie-based sessions. This API uses an Authorization header that a browser never
                // adds by itself, so a forged cross-site request carries no credentials and CSRF is switched off.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())            // rules from CorsConfig
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthenticationFilter(properties.getIngestApiKeys()),
                        BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter()))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").hasAnyRole("ANALYST", "ADMIN")
                        // ingestion: service API key only (people don't ingest)
                        .requestMatchers(HttpMethod.POST, "/api/transactions").hasRole("INGEST")
                        .requestMatchers(HttpMethod.GET, "/api/transactions", "/api/transactions/**")
                                .hasAnyRole("ANALYST", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/transactions/*/review").hasAnyRole("ANALYST", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/feedback/stats", "/api/feedback/models",
                                "/api/feedback/registry").hasAnyRole("ANALYST", "ADMIN")
                        // training data export, re-drive, DLQ and any future admin route
                        .requestMatchers("/api/feedback/labels", "/api/admin/**").hasRole("ADMIN")
                        .anyRequest().hasRole("ADMIN"));       // anything not listed above is admin-only by default
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain closedChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()      // Spring's error page (404s and such)
                        // The API description and Swagger UI: public, they describe routes but contain no data or secrets.
                        // Turn them off with springdoc.api-docs.enabled=false and springdoc.swagger-ui.enabled=false.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    // The token's "role" claim becomes the Spring authority ROLE_<role>
    private JwtAuthenticationConverter roleConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            return role == null ? List.<GrantedAuthority>of() : List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }
}
