package com.dex.backend.api;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.market.MarketEntity;
import com.dex.backend.persistence.market.MarketEntityId;
import com.dex.backend.persistence.market.MarketRepository;
import com.dex.backend.persistence.order.OrderEntity;
import com.dex.backend.persistence.order.OrderEntityId;
import com.dex.backend.persistence.order.OrderRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderApiIntegrationTest {

    private static final String CONFIGURED_DEPLOYMENT = "integration-test";
    private static final String OTHER_DEPLOYMENT = "other-deployment";
    private static final BigInteger MARKET_ID = BigInteger.ONE;
    private static final EthereumAddress OWNER = address(0xabcdeL);
    private static final EthereumAddress OTHER_OWNER = address(0xfedcbL);

    private final MockMvc mockMvc;
    private final OrderRepository orderRepository;
    private final MarketRepository marketRepository;
    private final ChainDeploymentRepository deploymentRepository;

    @Autowired
    OrderApiIntegrationTest(
            MockMvc mockMvc,
            OrderRepository orderRepository,
            MarketRepository marketRepository,
            ChainDeploymentRepository deploymentRepository
    ) {
        this.mockMvc = mockMvc;
        this.orderRepository = orderRepository;
        this.marketRepository = marketRepository;
        this.deploymentRepository = deploymentRepository;
    }

    @BeforeEach
    void clearProjection() {
        clearProjectionData();
    }

    @AfterEach
    void clearProjectionAfterTest() {
        clearProjectionData();
    }

    private void clearProjectionData() {
        orderRepository.deleteAll();
        marketRepository.deleteAll();
        deploymentRepository.deleteAll();
    }

    @Test
    void listsNormalizedWalletOrdersNewestFirstAndMapsConfirmedState() throws Exception {
        BigInteger largeOrderId = new BigInteger("9007199254740993");
        BigInteger largePrice = new BigInteger("1000000000000000000000000000000");
        BigInteger originalLots = new BigInteger("9007199254741099");
        BigInteger remainingLots = originalLots.subtract(BigInteger.valueOf(40));

        saveDeployment(CONFIGURED_DEPLOYMENT, 1);
        saveDeployment(OTHER_DEPLOYMENT, 2);
        saveMarket(CONFIGURED_DEPLOYMENT, MARKET_ID);
        saveMarket(OTHER_DEPLOYMENT, MARKET_ID);

        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, largeOrderId, OWNER, OrderSide.BUY,
                largePrice, originalLots, remainingLots, OrderStatus.PARTIALLY_FILLED,
                BigInteger.valueOf(4), BigInteger.ZERO, BigInteger.ZERO, 1);
        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(4), OWNER, OrderSide.SELL,
                BigInteger.valueOf(400), BigInteger.TEN, BigInteger.valueOf(5), OrderStatus.CANCELLED,
                BigInteger.valueOf(3), BigInteger.ZERO, BigInteger.ZERO, 2);
        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(3), OWNER, OrderSide.BUY,
                BigInteger.valueOf(300), BigInteger.TEN, BigInteger.ZERO, OrderStatus.FILLED,
                BigInteger.valueOf(2), BigInteger.ZERO, BigInteger.ZERO, 3);
        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(2), OWNER, OrderSide.SELL,
                BigInteger.valueOf(200), BigInteger.TEN, BigInteger.TEN, OrderStatus.OPEN,
                BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO, 4);
        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(20), OTHER_OWNER, OrderSide.BUY,
                BigInteger.valueOf(500), BigInteger.TEN, BigInteger.TEN, OrderStatus.OPEN,
                BigInteger.valueOf(5), BigInteger.ZERO, BigInteger.ZERO, 5);
        saveOrder(OTHER_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(21), OWNER, OrderSide.BUY,
                BigInteger.valueOf(600), BigInteger.TEN, BigInteger.TEN, OrderStatus.OPEN,
                BigInteger.valueOf(6), BigInteger.ZERO, BigInteger.ZERO, 6);
        orderRepository.flush();

        mockMvc.perform(get("/api/v1/orders").param("owner", OWNER.value().toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items.length()", is(4)))
                .andExpect(jsonPath("$.items[0].orderId", is(largeOrderId.toString())))
                .andExpect(jsonPath("$.items[0].marketId", is("1")))
                .andExpect(jsonPath("$.items[0].owner", is(OWNER.value())))
                .andExpect(jsonPath("$.items[0].side", is("BUY")))
                .andExpect(jsonPath("$.items[0].limitPriceQuoteAtomsPerLot", is(largePrice.toString())))
                .andExpect(jsonPath("$.items[0].originalLots", is(originalLots.toString())))
                .andExpect(jsonPath("$.items[0].filledLots", is("40")))
                .andExpect(jsonPath("$.items[0].remainingLots", is(remainingLots.toString())))
                .andExpect(jsonPath("$.items[0].status", is("PARTIALLY_FILLED")))
                .andExpect(jsonPath("$.items[0].creationPosition.blockNumber", is("4")))
                .andExpect(jsonPath("$.items[0].creationPosition.transactionIndex", is("0")))
                .andExpect(jsonPath("$.items[0].creationPosition.logIndex", is("0")))
                .andExpect(jsonPath("$.items[0].creationTransactionHash", is(hash(10_001).value())))
                .andExpect(jsonPath("$.items[0].blockTimestamp", is("2026-01-01T00:00:01Z")))
                .andExpect(jsonPath("$.items[1].status", is("CANCELLED")))
                .andExpect(jsonPath("$.items[2].status", is("FILLED")))
                .andExpect(jsonPath("$.items[3].status", is("OPEN")))
                .andExpect(jsonPath("$.nextCursor", nullValue()));
    }

    @Test
    void appliesOptionalMarketAndStatusFilters() throws Exception {
        saveDeployment(CONFIGURED_DEPLOYMENT, 1);
        saveMarket(CONFIGURED_DEPLOYMENT, MARKET_ID);
        saveMarket(CONFIGURED_DEPLOYMENT, BigInteger.TWO);

        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.ONE, OWNER, OrderSide.BUY,
                BigInteger.TEN, BigInteger.TEN, BigInteger.TEN, OrderStatus.OPEN,
                BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO, 1);
        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.TWO, OWNER, OrderSide.SELL,
                BigInteger.TEN, BigInteger.TEN, BigInteger.ZERO, OrderStatus.FILLED,
                BigInteger.TWO, BigInteger.ZERO, BigInteger.ZERO, 2);
        saveOrder(CONFIGURED_DEPLOYMENT, BigInteger.TWO, BigInteger.valueOf(3), OWNER, OrderSide.BUY,
                BigInteger.TEN, BigInteger.TEN, BigInteger.TEN, OrderStatus.OPEN,
                BigInteger.valueOf(3), BigInteger.ZERO, BigInteger.ZERO, 3);
        orderRepository.flush();

        mockMvc.perform(get("/api/v1/orders")
                        .param("owner", OWNER.value())
                        .param("marketId", "1")
                        .param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].orderId", is("1")));

        mockMvc.perform(get("/api/v1/orders")
                        .param("owner", OWNER.value())
                        .param("marketId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor", nullValue()));
    }

    @Test
    void traversesStableCursorPagesWithoutDuplicates() throws Exception {
        saveDeployment(CONFIGURED_DEPLOYMENT, 1);
        saveMarket(CONFIGURED_DEPLOYMENT, MARKET_ID);

        saveOpenOrder(5, 3, 1, 0, 5);
        saveOpenOrder(4, 3, 0, 2, 4);
        saveOpenOrder(3, 3, 0, 2, 3);
        saveOpenOrder(2, 2, 0, 0, 2);
        saveOpenOrder(1, 1, 0, 0, 1);
        orderRepository.flush();

        MvcResult firstPage = mockMvc.perform(get("/api/v1/orders")
                        .param("owner", OWNER.value())
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andExpect(jsonPath("$.items[0].orderId", is("5")))
                .andExpect(jsonPath("$.items[1].orderId", is("4")))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        String firstCursor = JsonPath.read(
                firstPage.getResponse().getContentAsString(),
                "$.nextCursor"
        );

        MvcResult secondPage = mockMvc.perform(get("/api/v1/orders")
                        .param("owner", OWNER.value())
                        .param("limit", "2")
                        .param("cursor", firstCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andExpect(jsonPath("$.items[0].orderId", is("3")))
                .andExpect(jsonPath("$.items[1].orderId", is("2")))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        String secondCursor = JsonPath.read(
                secondPage.getResponse().getContentAsString(),
                "$.nextCursor"
        );

        mockMvc.perform(get("/api/v1/orders")
                        .param("owner", OWNER.value())
                        .param("limit", "2")
                        .param("cursor", secondCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].orderId", is("1")))
                .andExpect(jsonPath("$.nextCursor", nullValue()));
    }

    @Test
    void appliesDefaultAndMaximumPageLimits() throws Exception {
        saveDeployment(CONFIGURED_DEPLOYMENT, 1);
        saveMarket(CONFIGURED_DEPLOYMENT, MARKET_ID);

        for (int orderId = 1; orderId <= 51; orderId++) {
            saveOpenOrder(orderId, orderId, 0, 0, orderId);
        }
        orderRepository.flush();

        mockMvc.perform(get("/api/v1/orders").param("owner", OWNER.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(50)))
                .andExpect(jsonPath("$.items[0].orderId", is("51")))
                .andExpect(jsonPath("$.items[49].orderId", is("2")))
                .andExpect(jsonPath("$.nextCursor").isString());

        mockMvc.perform(get("/api/v1/orders")
                        .param("owner", OWNER.value())
                        .param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(51)))
                .andExpect(jsonPath("$.nextCursor", nullValue()));
    }

    @Test
    void returnsOneConfiguredDeploymentOrder() throws Exception {
        saveDeployment(CONFIGURED_DEPLOYMENT, 1);
        saveMarket(CONFIGURED_DEPLOYMENT, MARKET_ID);
        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(7), OWNER, OrderSide.SELL,
                BigInteger.valueOf(200), BigInteger.TEN, BigInteger.TEN, OrderStatus.OPEN,
                BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO, 7);
        orderRepository.flush();

        mockMvc.perform(get("/api/v1/orders/{orderId}", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId", is("7")))
                .andExpect(jsonPath("$.owner", is(OWNER.value())))
                .andExpect(jsonPath("$.status", is("OPEN")));
    }

    @Test
    void returnsNotFoundForOrderExistingOnlyInAnotherDeployment() throws Exception {
        saveDeployment(OTHER_DEPLOYMENT, 2);
        saveMarket(OTHER_DEPLOYMENT, MARKET_ID);
        saveOrder(OTHER_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(7), OWNER, OrderSide.SELL,
                BigInteger.valueOf(200), BigInteger.TEN, BigInteger.TEN, OrderStatus.OPEN,
                BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO, 7);
        orderRepository.flush();

        mockMvc.perform(get("/api/v1/orders/{orderId}", 7))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", is("urn:dex:problem:not-found")))
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.instance", is("/api/v1/orders/7")));
    }

    @Test
    void rejectsMissingOrInvalidOwner() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field", is("owner")));

        mockMvc.perform(get("/api/v1/orders").param("owner", "not-an-address"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type", is("urn:dex:problem:invalid-request")))
                .andExpect(jsonPath("$.errors[0].field", is("owner")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "-1"})
    void rejectsInvalidMarketIds(String marketId) throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .param("owner", OWNER.value())
                        .param("marketId", marketId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field", is("marketId")));
    }

    @Test
    void rejectsInvalidStatus() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .param("owner", OWNER.value())
                        .param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field", is("status")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "-1"})
    void rejectsInvalidOrderIds(String orderId) throws Exception {
        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field", is("orderId")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "0", "201"})
    void rejectsInvalidLimits(String limit) throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .param("owner", OWNER.value())
                        .param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field", is("limit")));
    }

    @Test
    void rejectsMalformedAndUnsupportedCursors() throws Exception {
        String unsupportedCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "v2:1:0:0:1".getBytes(StandardCharsets.UTF_8)
        );
        String negativeCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "v1:-1:0:0:1".getBytes(StandardCharsets.UTF_8)
        );
        String incompleteCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "v1:1:0:0".getBytes(StandardCharsets.UTF_8)
        );

        for (String cursor : new String[]{
                "not_base64%",
                unsupportedCursor,
                negativeCursor,
                incompleteCursor
        }) {
            mockMvc.perform(get("/api/v1/orders")
                            .param("owner", OWNER.value())
                            .param("cursor", cursor))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type", is("urn:dex:problem:invalid-request")))
                    .andExpect(jsonPath("$.errors[0].field", is("cursor")))
                    .andExpect(jsonPath("$.errors[0].message", is("has an invalid value")));
        }
    }

    private void saveOpenOrder(long orderId, long block, long transaction, long log, long seed) {
        saveOrder(
                CONFIGURED_DEPLOYMENT,
                MARKET_ID,
                BigInteger.valueOf(orderId),
                OWNER,
                OrderSide.BUY,
                BigInteger.valueOf(100),
                BigInteger.TEN,
                BigInteger.TEN,
                OrderStatus.OPEN,
                BigInteger.valueOf(block),
                BigInteger.valueOf(transaction),
                BigInteger.valueOf(log),
                seed
        );
    }

    private void saveDeployment(String deploymentId, long identitySeed) {
        deploymentRepository.saveAndFlush(new ChainDeploymentEntity(
                deploymentId,
                BlockchainMode.MOCK,
                BigInteger.valueOf(identitySeed),
                address(identitySeed),
                address(identitySeed),
                BigInteger.ZERO,
                hash(identitySeed)
        ));
    }

    private void saveMarket(String deploymentId, BigInteger marketId) {
        marketRepository.saveAndFlush(new MarketEntity(
                new MarketEntityId(deploymentId, marketId),
                address(100),
                address(101),
                "TKA",
                "TKB",
                18,
                18,
                BigInteger.valueOf(1_000_000),
                true
        ));
    }

    private void saveOrder(
            String deploymentId,
            BigInteger marketId,
            BigInteger orderId,
            EthereumAddress owner,
            OrderSide side,
            BigInteger price,
            BigInteger originalLots,
            BigInteger remainingLots,
            OrderStatus status,
            BigInteger blockNumber,
            BigInteger transactionIndex,
            BigInteger logIndex,
            long identitySeed
    ) {
        orderRepository.save(new OrderEntity(
                new OrderEntityId(deploymentId, orderId),
                marketId,
                owner,
                side,
                price,
                originalLots,
                remainingLots,
                status,
                blockNumber,
                transactionIndex,
                logIndex,
                hash(10_000 + identitySeed),
                hash(20_000 + identitySeed),
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(identitySeed)
        ));
    }

    private static EthereumAddress address(long value) {
        return new EthereumAddress("0x" + String.format("%040x", value));
    }

    private static Hash256 hash(long value) {
        return new Hash256("0x" + String.format("%064x", value));
    }
}
