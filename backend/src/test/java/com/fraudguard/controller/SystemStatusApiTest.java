package com.fraudguard.controller;

import static com.fraudguard.TestAuth.admin;
import static com.fraudguard.TestAuth.analyst;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fraudguard.config.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// The admin Operations tab's status card: real database and Redis (Testcontainers), ML switched off in the test profile.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class SystemStatusApiTest {

    @Autowired MockMvc mvc;

    @Test
    void adminSeesDependenciesCircuitAndBacklogs() throws Exception {
        mvc.perform(get("/api/admin/system-status").with(admin("root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.redis").value("UP"))
                .andExpect(jsonPath("$.mlCircuit").value("DISABLED"))       // the test profile turns ML off
                .andExpect(jsonPath("$.outboxPending").isNumber())
                .andExpect(jsonPath("$.stuckPending").isNumber())
                .andExpect(jsonPath("$.stuckAfterSeconds").value(120))
                .andExpect(jsonPath("$.checkedAt").exists());
    }

    @Test
    void analystCannotSeeIt() throws Exception {
        mvc.perform(get("/api/admin/system-status").with(analyst("a1"))).andExpect(status().isForbidden());
    }

    @Test
    void anonymousGets401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/system-status"))
                .andExpect(status().isUnauthorized());
    }
}
