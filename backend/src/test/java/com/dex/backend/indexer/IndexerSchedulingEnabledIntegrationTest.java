package com.dex.backend.indexer;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.config.DexProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "dex.indexer.enabled=true",
        "dex.indexer.poll-interval=1h"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class IndexerSchedulingEnabledIntegrationTest {

    @Autowired
    private DexProperties properties;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void enablesWorkerAndSchedulingConfiguration() {
        assertTrue(properties.indexer().enabled());
        assertTrue(applicationContext.containsBean("indexerPollingWorker"));
        assertTrue(applicationContext.containsBean("indexerSchedulingConfiguration"));
    }
}
