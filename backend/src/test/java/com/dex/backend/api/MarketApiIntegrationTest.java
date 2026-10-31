package com.dex.backend.api;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.market.MarketEntity;
import com.dex.backend.persistence.market.MarketEntityId;
import com.dex.backend.persistence.market.MarketRepository;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "dex.api.cors-origins=http://frontend.example")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({
        TestcontainersConfiguration.class,
        MarketApiIntegrationTest.ApiTestConfiguration.class
})
class MarketApiIntegrationTest {

    private static final String CONFIGURED_DEPLOYMENT = "integration-test";
    private static final String OTHER_DEPLOYMENT = "other-deployment";
    private static final String ALLOWED_ORIGIN = "http://frontend.example";

    private final MockMvc mockMvc;
    private final MarketRepository marketRepository;
    private final ChainDeploymentRepository deploymentRepository;

    @Autowired
    MarketApiIntegrationTest(
            MockMvc mockMvc,
            MarketRepository marketRepository,
            ChainDeploymentRepository deploymentRepository
    ) {
        this.mockMvc = mockMvc;
        this.marketRepository = marketRepository;
        this.deploymentRepository = deploymentRepository;
    }

    @BeforeEach
    void clearProjection() {
        marketRepository.deleteAll();
        deploymentRepository.deleteAll();
    }

    @Test
    void returnsEmptyItemsWhenNoMarketsExist() throws Exception {
        mockMvc.perform(get("/api/v1/markets"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void returnsConfiguredDeploymentMarketsInNumericOrder() throws Exception {
        saveDeployment(CONFIGURED_DEPLOYMENT, 1, '1', 'a');
        saveDeployment(OTHER_DEPLOYMENT, 2, '2', 'b');

        marketRepository.save(new MarketEntity(
                new MarketEntityId(CONFIGURED_DEPLOYMENT, new BigInteger("9007199254740993")),
                address('C'),
                address('D'),
                "LARGE",
                "USD",
                18,
                6,
                new BigInteger("1000000000000000000000000000000"),
                false
        ));
        marketRepository.save(new MarketEntity(
                new MarketEntityId(CONFIGURED_DEPLOYMENT, BigInteger.TWO),
                address('A'),
                address('B'),
                "TKA",
                "TKB",
                18,
                8,
                new BigInteger("1000000000000000000"),
                true
        ));
        marketRepository.save(new MarketEntity(
                new MarketEntityId(OTHER_DEPLOYMENT, BigInteger.ONE),
                address('E'),
                address('F'),
                "OTHER",
                "USD",
                18,
                6,
                BigInteger.ONE,
                true
        ));
        marketRepository.flush();

        mockMvc.perform(get("/api/v1/markets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].marketId", is("2")))
                .andExpect(jsonPath("$.items[0].baseToken.address", is(address('a').value())))
                .andExpect(jsonPath("$.items[0].baseToken.symbol", is("TKA")))
                .andExpect(jsonPath("$.items[0].baseToken.decimals", is(18)))
                .andExpect(jsonPath("$.items[0].quoteToken.address", is(address('b').value())))
                .andExpect(jsonPath("$.items[0].quoteToken.symbol", is("TKB")))
                .andExpect(jsonPath("$.items[0].quoteToken.decimals", is(8)))
                .andExpect(jsonPath("$.items[0].baseLotSizeAtoms", is("1000000000000000000")))
                .andExpect(jsonPath("$.items[0].quantityUnit", is("LOTS")))
                .andExpect(jsonPath("$.items[0].priceUnit", is("QUOTE_ATOMS_PER_LOT")))
                .andExpect(jsonPath("$.items[0].active", is(true)))
                .andExpect(jsonPath("$.items[1].marketId", is("9007199254740993")))
                .andExpect(jsonPath(
                        "$.items[1].baseLotSizeAtoms",
                        is("1000000000000000000000000000000")
                ))
                .andExpect(jsonPath("$.items[1].active", is(false)));
    }

    @Test
    void returnsProblemDetailsForValidationFailure() throws Exception {
        mockMvc.perform(get("/api/v1/test/validated").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", is("urn:dex:problem:invalid-request")))
                .andExpect(jsonPath("$.title", is("Invalid request")))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.detail", is("One or more request values are invalid.")))
                .andExpect(jsonPath("$.instance", is("/api/v1/test/validated")))
                .andExpect(jsonPath("$.errors[0].field", is("limit")));
    }

    @Test
    void returnsProblemDetailsForTypeMismatch() throws Exception {
        mockMvc.perform(get("/api/v1/test/validated").param("limit", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", is("urn:dex:problem:invalid-request")))
                .andExpect(jsonPath("$.errors[0].field", is("limit")))
                .andExpect(jsonPath("$.errors[0].message", is("has an invalid value")));
    }

    @Test
    void returnsProblemDetailsForUnknownApiResource() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", is("urn:dex:problem:not-found")))
                .andExpect(jsonPath("$.title", is("Resource not found")))
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.instance", is("/api/v1/does-not-exist")));
    }

    @Test
    void rejectsWriteMethodWithProblemDetails() throws Exception {
        mockMvc.perform(post("/api/v1/markets"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", is("urn:dex:problem:invalid-request")))
                .andExpect(jsonPath("$.title", is("Method not allowed")))
                .andExpect(jsonPath("$.status", is(405)))
                .andExpect(jsonPath("$.instance", is("/api/v1/markets")));
    }

    @Test
    void sanitizesUnexpectedFailures() throws Exception {
        mockMvc.perform(get("/api/v1/test/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", is("urn:dex:problem:internal-error")))
                .andExpect(jsonPath("$.detail", is("The request could not be completed.")))
                .andExpect(content().string(not(containsString("sensitive-database-detail"))));
    }

    @Test
    void allowsConfiguredApiOrigin() throws Exception {
        mockMvc.perform(get("/api/v1/markets").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    void allowsConfiguredReadPreflightWithoutCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/markets")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,OPTIONS"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void rejectsUnconfiguredApiOrigin() throws Exception {
        mockMvc.perform(get("/api/v1/markets").header(HttpHeaders.ORIGIN, "http://untrusted.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void doesNotApplyApiCorsConfigurationToActuator() throws Exception {
        mockMvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    private void saveDeployment(
            String deploymentId,
            long chainId,
            char addressCharacter,
            char hashCharacter
    ) {
        deploymentRepository.saveAndFlush(new ChainDeploymentEntity(
                deploymentId,
                BlockchainMode.MOCK,
                BigInteger.valueOf(chainId),
                address(addressCharacter),
                address(addressCharacter),
                BigInteger.ZERO,
                hash(hashCharacter)
        ));
    }

    private static EthereumAddress address(char character) {
        return new EthereumAddress("0x" + String.valueOf(character).repeat(40));
    }

    private static Hash256 hash(char character) {
        return new Hash256("0x" + String.valueOf(character).repeat(64));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ApiTestConfiguration {

        @Bean
        ApiTestController apiTestController() {
            return new ApiTestController();
        }
    }

    @RestController
    static class ApiTestController {

        @GetMapping("/api/v1/test/validated")
        String validated(@RequestParam @Min(1) int limit) {
            return Integer.toString(limit);
        }

        @GetMapping("/api/v1/test/failure")
        String failure() {
            throw new IllegalStateException("sensitive-database-detail");
        }
    }
}
