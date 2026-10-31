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
import com.dex.backend.persistence.trade.TradeEntity;
import com.dex.backend.persistence.trade.TradeEntityId;
import com.dex.backend.persistence.trade.TradeRepository;
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
class TradeApiIntegrationTest {

    private static final String CONFIGURED_DEPLOYMENT = "integration-test";
    private static final String OTHER_DEPLOYMENT = "other-deployment";
    private static final BigInteger MARKET_ID = BigInteger.ONE;

    private final MockMvc mockMvc;
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final MarketRepository marketRepository;
    private final ChainDeploymentRepository deploymentRepository;

    @Autowired
    TradeApiIntegrationTest(
            MockMvc mockMvc,
            TradeRepository tradeRepository,
            OrderRepository orderRepository,
            MarketRepository marketRepository,
            ChainDeploymentRepository deploymentRepository
    ) {
        this.mockMvc = mockMvc;
        this.tradeRepository = tradeRepository;
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
        tradeRepository.deleteAll();
        orderRepository.deleteAll();
        marketRepository.deleteAll();
        deploymentRepository.deleteAll();
    }

    @Test
    void returnsEmptyPageForExistingMarket() throws Exception {
        saveDeployment(CONFIGURED_DEPLOYMENT, 1);
        saveMarketWithOrders(CONFIGURED_DEPLOYMENT, MARKET_ID, true, 1);

        mockMvc.perform(get("/api/v1/markets/{marketId}/trades", MARKET_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor", nullValue()));
    }

    @Test
    void returnsNotFoundForMarketExistingOnlyInAnotherDeployment() throws Exception {
        saveDeployment(OTHER_DEPLOYMENT, 2);
        saveMarketWithOrders(OTHER_DEPLOYMENT, MARKET_ID, true, 2);

        mockMvc.perform(get("/api/v1/markets/{marketId}/trades", MARKET_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", is("urn:dex:problem:not-found")))
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.instance", is("/api/v1/markets/1/trades")));
    }

    @Test
    void returnsInactiveMarketTradesNewestFirstWithCompleteConfirmedState() throws Exception {
        BigInteger largeTradeId = new BigInteger("9007199254740993");
        BigInteger largeFill = new BigInteger("9007199254740995");
        BigInteger largePrice = new BigInteger("1000000000000000000000000000000");

        saveDeployment(CONFIGURED_DEPLOYMENT, 1);
        saveDeployment(OTHER_DEPLOYMENT, 2);
        saveMarketWithOrders(CONFIGURED_DEPLOYMENT, MARKET_ID, false, 1);
        saveMarketWithOrders(CONFIGURED_DEPLOYMENT, BigInteger.TWO, true, 2);
        saveMarketWithOrders(OTHER_DEPLOYMENT, MARKET_ID, true, 3);

        saveTrade(CONFIGURED_DEPLOYMENT, MARKET_ID, null, largeFill, largePrice,
                BigInteger.ZERO, BigInteger.valueOf(60), BigInteger.valueOf(20),
                BigInteger.ONE, BigInteger.valueOf(3), 50, 1);
        saveTrade(CONFIGURED_DEPLOYMENT, MARKET_ID, largeTradeId, BigInteger.valueOf(5),
                BigInteger.valueOf(200), BigInteger.valueOf(7), BigInteger.valueOf(8),
                BigInteger.valueOf(20), BigInteger.ONE, BigInteger.TWO, 50, 2);
        saveTrade(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(3), BigInteger.ONE,
                BigInteger.valueOf(100), BigInteger.valueOf(9), BigInteger.valueOf(10),
                BigInteger.valueOf(20), BigInteger.ZERO, BigInteger.valueOf(9), 51, 3);
        saveTrade(CONFIGURED_DEPLOYMENT, BigInteger.TWO, BigInteger.valueOf(4), BigInteger.ONE,
                BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(30),
                BigInteger.ZERO, BigInteger.ZERO, 52, 4);
        saveTrade(OTHER_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(5), BigInteger.ONE,
                BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(40),
                BigInteger.ZERO, BigInteger.ZERO, 53, 5);
        tradeRepository.flush();

        mockMvc.perform(get("/api/v1/markets/{marketId}/trades", MARKET_ID).param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(3)))
                .andExpect(jsonPath("$.items[0].tradeId", nullValue()))
                .andExpect(jsonPath("$.items[0].marketId", is("1")))
                .andExpect(jsonPath("$.items[0].buyOrderId", is("100")))
                .andExpect(jsonPath("$.items[0].sellOrderId", is("101")))
                .andExpect(jsonPath("$.items[0].fillLots", is(largeFill.toString())))
                .andExpect(jsonPath(
                        "$.items[0].executionPriceQuoteAtomsPerLot",
                        is(largePrice.toString())
                ))
                .andExpect(jsonPath("$.items[0].buyOrderRemainingLots", is("0")))
                .andExpect(jsonPath("$.items[0].sellOrderRemainingLots", is("60")))
                .andExpect(jsonPath("$.items[0].executionPosition.blockNumber", is("20")))
                .andExpect(jsonPath("$.items[0].executionPosition.transactionIndex", is("1")))
                .andExpect(jsonPath("$.items[0].executionPosition.logIndex", is("3")))
                .andExpect(jsonPath("$.items[0].transactionHash", is(hash(30_050).value())))
                .andExpect(jsonPath("$.items[0].blockTimestamp", is("2026-01-01T00:00:01Z")))
                .andExpect(jsonPath("$.items[1].tradeId", is(largeTradeId.toString())))
                .andExpect(jsonPath("$.items[1].executionPosition.logIndex", is("2")))
                .andExpect(jsonPath("$.items[2].tradeId", is("3")))
                .andExpect(jsonPath("$.nextCursor", nullValue()));
    }

    @Test
    void traversesStableCursorPagesWithoutDuplicates() throws Exception {
        saveDeployment(CONFIGURED_DEPLOYMENT, 1);
        saveMarketWithOrders(CONFIGURED_DEPLOYMENT, MARKET_ID, true, 1);

        saveSimpleTrade(5, 3, 1, 0, 5);
        saveSimpleTrade(4, 3, 0, 2, 4);
        saveSimpleTrade(3, 3, 0, 1, 3);
        saveSimpleTrade(2, 2, 0, 0, 2);
        saveSimpleTrade(1, 1, 0, 0, 1);
        tradeRepository.flush();

        MvcResult firstPage = mockMvc.perform(get("/api/v1/markets/{marketId}/trades", MARKET_ID)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andExpect(jsonPath("$.items[0].tradeId", is("5")))
                .andExpect(jsonPath("$.items[1].tradeId", is("4")))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        String firstCursor = JsonPath.read(
                firstPage.getResponse().getContentAsString(),
                "$.nextCursor"
        );

        MvcResult secondPage = mockMvc.perform(get("/api/v1/markets/{marketId}/trades", MARKET_ID)
                        .param("limit", "2")
                        .param("cursor", firstCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andExpect(jsonPath("$.items[0].tradeId", is("3")))
                .andExpect(jsonPath("$.items[1].tradeId", is("2")))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        String secondCursor = JsonPath.read(
                secondPage.getResponse().getContentAsString(),
                "$.nextCursor"
        );

        mockMvc.perform(get("/api/v1/markets/{marketId}/trades", MARKET_ID)
                        .param("limit", "2")
                        .param("cursor", secondCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].tradeId", is("1")))
                .andExpect(jsonPath("$.nextCursor", nullValue()));
    }

    @Test
    void appliesDefaultAndMaximumPageLimits() throws Exception {
        saveDeployment(CONFIGURED_DEPLOYMENT, 1);
        saveMarketWithOrders(CONFIGURED_DEPLOYMENT, MARKET_ID, true, 1);

        for (int tradeId = 1; tradeId <= 51; tradeId++) {
            saveSimpleTrade(tradeId, tradeId, 0, 0, tradeId);
        }
        tradeRepository.flush();

        mockMvc.perform(get("/api/v1/markets/{marketId}/trades", MARKET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(50)))
                .andExpect(jsonPath("$.items[0].tradeId", is("51")))
                .andExpect(jsonPath("$.items[49].tradeId", is("2")))
                .andExpect(jsonPath("$.nextCursor").isString());

        mockMvc.perform(get("/api/v1/markets/{marketId}/trades", MARKET_ID).param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(51)))
                .andExpect(jsonPath("$.nextCursor", nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "-1"})
    void rejectsInvalidMarketIds(String marketId) throws Exception {
        mockMvc.perform(get("/api/v1/markets/{marketId}/trades", marketId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", is("urn:dex:problem:invalid-request")))
                .andExpect(jsonPath("$.errors[0].field", is("marketId")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "0", "201"})
    void rejectsInvalidLimits(String limit) throws Exception {
        mockMvc.perform(get("/api/v1/markets/{marketId}/trades", MARKET_ID).param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field", is("limit")));
    }

    @Test
    void rejectsMalformedUnsupportedAndNegativeCursors() throws Exception {
        saveDeployment(CONFIGURED_DEPLOYMENT, 1);
        saveMarketWithOrders(CONFIGURED_DEPLOYMENT, MARKET_ID, true, 1);

        String unsupportedCursor = encodeCursorValue("v2:1:0:0");
        String negativeCursor = encodeCursorValue("v1:-1:0:0");
        String incompleteCursor = encodeCursorValue("v1:1:0");

        for (String cursor : new String[]{
                "not_base64%",
                unsupportedCursor,
                negativeCursor,
                incompleteCursor
        }) {
            mockMvc.perform(get("/api/v1/markets/{marketId}/trades", MARKET_ID)
                            .param("cursor", cursor))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type", is("urn:dex:problem:invalid-request")))
                    .andExpect(jsonPath("$.errors[0].field", is("cursor")))
                    .andExpect(jsonPath("$.errors[0].message", is("has an invalid value")));
        }
    }

    private void saveSimpleTrade(long tradeId, long block, long transaction, long log, long seed) {
        saveTrade(
                CONFIGURED_DEPLOYMENT,
                MARKET_ID,
                BigInteger.valueOf(tradeId),
                BigInteger.ONE,
                BigInteger.valueOf(100),
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.valueOf(block),
                BigInteger.valueOf(transaction),
                BigInteger.valueOf(log),
                seed,
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

    private void saveMarketWithOrders(
            String deploymentId,
            BigInteger marketId,
            boolean active,
            long identitySeed
    ) {
        marketRepository.saveAndFlush(new MarketEntity(
                new MarketEntityId(deploymentId, marketId),
                address(100),
                address(101),
                "TKA",
                "TKB",
                18,
                18,
                BigInteger.valueOf(1_000_000),
                active
        ));

        BigInteger orderLots = new BigInteger("1000000000000000000000000000000000000");
        saveOrder(
                deploymentId,
                marketId,
                buyOrderId(marketId),
                OrderSide.BUY,
                orderLots,
                identitySeed * 2
        );
        saveOrder(
                deploymentId,
                marketId,
                sellOrderId(marketId),
                OrderSide.SELL,
                orderLots,
                identitySeed * 2 + 1
        );
        orderRepository.flush();
    }

    private void saveOrder(
            String deploymentId,
            BigInteger marketId,
            BigInteger orderId,
            OrderSide side,
            BigInteger lots,
            long identitySeed
    ) {
        orderRepository.save(new OrderEntity(
                new OrderEntityId(deploymentId, orderId),
                marketId,
                address(1_000 + identitySeed),
                side,
                BigInteger.valueOf(100),
                lots,
                lots,
                OrderStatus.OPEN,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.valueOf(identitySeed),
                hash(10_000 + identitySeed),
                hash(20_000 + identitySeed),
                Instant.parse("2026-01-01T00:00:00Z")
        ));
    }

    private void saveTrade(
            String deploymentId,
            BigInteger marketId,
            BigInteger tradeId,
            BigInteger fillLots,
            BigInteger price,
            BigInteger buyRemaining,
            BigInteger sellRemaining,
            BigInteger blockNumber,
            BigInteger transactionIndex,
            BigInteger logIndex,
            long transactionHashSeed,
            long timestampSeed
    ) {
        tradeRepository.save(new TradeEntity(
                new TradeEntityId(
                        deploymentId,
                        hash(30_000 + transactionHashSeed),
                        logIndex
                ),
                tradeId,
                marketId,
                buyOrderId(marketId),
                sellOrderId(marketId),
                fillLots,
                price,
                buyRemaining,
                sellRemaining,
                blockNumber,
                transactionIndex,
                hash(40_000 + transactionHashSeed),
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(timestampSeed)
        ));
    }

    private static String encodeCursorValue(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static BigInteger buyOrderId(BigInteger marketId) {
        return marketId.multiply(BigInteger.valueOf(100));
    }

    private static BigInteger sellOrderId(BigInteger marketId) {
        return buyOrderId(marketId).add(BigInteger.ONE);
    }

    private static EthereumAddress address(long value) {
        return new EthereumAddress("0x" + String.format("%040x", value));
    }

    private static Hash256 hash(long value) {
        return new Hash256("0x" + String.format("%064x", value));
    }
}
