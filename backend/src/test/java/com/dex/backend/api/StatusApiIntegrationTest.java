package com.dex.backend.api;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigInteger;
import java.time.Instant;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StatusApiIntegrationTest {

    private static final String DEPLOYMENT_ID = "integration-test";
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    private final MockMvc mockMvc;
    private final IndexerCheckpointRepository checkpointRepository;
    private final ChainDeploymentRepository deploymentRepository;

    @Autowired
    StatusApiIntegrationTest(
            MockMvc mockMvc,
            IndexerCheckpointRepository checkpointRepository,
            ChainDeploymentRepository deploymentRepository
    ) {
        this.mockMvc = mockMvc;
        this.checkpointRepository = checkpointRepository;
        this.deploymentRepository = deploymentRepository;
    }

    @BeforeEach
    void clearOperationalState() {
        checkpointRepository.deleteAll();
        deploymentRepository.deleteAll();
    }

    @AfterEach
    void clearOperationalStateAfterTest() {
        checkpointRepository.deleteAll();
        deploymentRepository.deleteAll();
    }

    @Test
    void reportsUnregisteredDeploymentAndDisabledIndexerAsDegraded() throws Exception {
        mockMvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is("DEGRADED")))
                .andExpect(jsonPath("$.blockchainMode", is("MOCK")))
                .andExpect(jsonPath("$.deployment.deploymentId", is(DEPLOYMENT_ID)))
                .andExpect(jsonPath("$.deployment.registered", is(false)))
                .andExpect(jsonPath("$.deployment.chainId", nullValue()))
                .andExpect(jsonPath("$.deployment.dexAddress", nullValue()))
                .andExpect(jsonPath(
                        "$.deployment.marketRegistryAddress",
                        nullValue()
                ))
                .andExpect(jsonPath("$.deployment.deploymentBlockNumber", nullValue()))
                .andExpect(jsonPath("$.deployment.deploymentBlockHash", nullValue()))
                .andExpect(jsonPath("$.indexer.enabled", is(false)))
                .andExpect(jsonPath("$.indexer.state", is("DISABLED")))
                .andExpect(jsonPath("$.indexer.latestObservedBlockNumber", nullValue()))
                .andExpect(jsonPath("$.indexer.lastIndexedBlockNumber", nullValue()))
                .andExpect(jsonPath("$.indexer.indexingLagBlocks", nullValue()))
                .andExpect(jsonPath("$.indexer.confirmationDepth", is(0)))
                .andExpect(jsonPath("$.indexer.lastSuccessfulSyncAt", nullValue()))
                .andExpect(jsonPath("$.indexer.lastErrorAt", nullValue()))
                .andExpect(jsonPath("$.indexer.paused", is(false)))
                .andExpect(jsonPath("$.matcher.enabled", is(false)))
                .andExpect(jsonPath("$.matcher.state", is("DISABLED")))
                .andExpect(jsonPath("$.executor.enabled", is(false)))
                .andExpect(jsonPath("$.executor.state", is("DISABLED")));
    }

    @Test
    void returnsRegisteredIdentityAndCheckpointAsSafeDecimalStrings() throws Exception {
        String sensitiveError = "rpc password=do-not-expose";
        String sensitivePauseReason = "private-key=do-not-expose";
        deploymentRepository.saveAndFlush(new ChainDeploymentEntity(
                DEPLOYMENT_ID,
                BlockchainMode.MOCK,
                new BigInteger("9007199254740993"),
                address(1),
                address(2),
                new BigInteger("9007199254740995"),
                hash(1)
        ));
        checkpointRepository.saveAndFlush(new IndexerCheckpointEntity(
                DEPLOYMENT_ID,
                new BigInteger("9007199254741000"),
                hash(2),
                new BigInteger("9007199254741010"),
                hash(3),
                Instant.parse("2026-01-01T12:00:00Z"),
                Instant.parse("2026-01-01T12:05:00Z"),
                sensitiveError,
                true,
                sensitivePauseReason
        ));

        mockMvc.perform(get("/api/v1/status").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(jsonPath("$.status", is("DEGRADED")))
                .andExpect(jsonPath("$.deployment.registered", is(true)))
                .andExpect(jsonPath("$.deployment.chainId", is("9007199254740993")))
                .andExpect(jsonPath("$.deployment.dexAddress", is(address(1).value())))
                .andExpect(jsonPath(
                        "$.deployment.marketRegistryAddress",
                        is(address(2).value())
                ))
                .andExpect(jsonPath(
                        "$.deployment.deploymentBlockNumber",
                        is("9007199254740995")
                ))
                .andExpect(jsonPath("$.deployment.deploymentBlockHash", is(hash(1).value())))
                .andExpect(jsonPath("$.indexer.latestObservedBlockNumber", is("9007199254741010")))
                .andExpect(jsonPath("$.indexer.lastIndexedBlockNumber", is("9007199254741000")))
                .andExpect(jsonPath("$.indexer.indexingLagBlocks", is("10")))
                .andExpect(jsonPath("$.indexer.lastSuccessfulSyncAt", is("2026-01-01T12:00:00Z")))
                .andExpect(jsonPath("$.indexer.lastErrorAt", is("2026-01-01T12:05:00Z")))
                .andExpect(jsonPath("$.indexer.paused", is(true)))
                .andExpect(content().string(not(containsString(sensitiveError))))
                .andExpect(content().string(not(containsString(sensitivePauseReason))));
    }

    private static EthereumAddress address(long value) {
        return new EthereumAddress("0x" + String.format("%040x", value));
    }

    private static Hash256 hash(long value) {
        return new Hash256("0x" + String.format("%064x", value));
    }
}
