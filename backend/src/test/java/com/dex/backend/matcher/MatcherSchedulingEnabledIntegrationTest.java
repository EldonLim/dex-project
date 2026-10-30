package com.dex.backend.matcher;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.config.DexProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "dex.matcher.enabled=true",
        "dex.matcher.poll-interval=1h"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class MatcherSchedulingEnabledIntegrationTest {

    @Autowired
    private DexProperties properties;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void enablesWorkerAndIndependentSchedulingConfiguration() {
        assertTrue(properties.matcher().enabled());
        assertEquals(Duration.ofHours(1), properties.matcher().pollInterval());
        assertTrue(applicationContext.containsBean("matcherPollingWorker"));
        assertTrue(applicationContext.containsBean(
                "matcherSchedulingConfiguration"
        ));
    }

    @Test
    void statusSafelyReportsStartingMatcherWithHttp200AndCors() throws Exception {
        mockMvc.perform(get("/api/v1/status")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin",
                        "http://localhost:5173"
                ))
                .andExpect(jsonPath("$.matcher.enabled").value(true))
                .andExpect(jsonPath("$.matcher.state").value("STARTING"));
    }
}
