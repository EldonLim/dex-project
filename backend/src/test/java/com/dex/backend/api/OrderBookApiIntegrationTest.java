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

import java.math.BigInteger;
import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderBookApiIntegrationTest {

    private static final String CONFIGURED_DEPLOYMENT = "integration-test";
    private static final String OTHER_DEPLOYMENT = "other-deployment";
    private static final BigInteger MARKET_ID = BigInteger.ONE;

    private final MockMvc mockMvc;
    private final OrderRepository orderRepository;
    private final MarketRepository marketRepository;
    private final ChainDeploymentRepository deploymentRepository;

    @Autowired
    OrderBookApiIntegrationTest(
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
        orderRepository.deleteAll();
        marketRepository.deleteAll();
        deploymentRepository.deleteAll();
    }

    @Test
    void returnsEmptySidesAndDefaultLimitForExistingMarket() throws Exception {
        saveDeployment(CONFIGURED_DEPLOYMENT, 1);
        saveMarket(CONFIGURED_DEPLOYMENT, MARKET_ID, true);

        mockMvc.perform(get("/api/v1/markets/{marketId}/orderbook", MARKET_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.marketId", is("1")))
                .andExpect(jsonPath("$.limit", is(50)))
                .andExpect(jsonPath("$.bids.items").isEmpty())
                .andExpect(jsonPath("$.bids.hasMore", is(false)))
                .andExpect(jsonPath("$.asks.items").isEmpty())
                .andExpect(jsonPath("$.asks.hasMore", is(false)));
    }

    @Test
    void returnsNotFoundForMissingConfiguredDeploymentMarket() throws Exception {
        saveDeployment(OTHER_DEPLOYMENT, 2);
        saveMarket(OTHER_DEPLOYMENT, MARKET_ID, true);

        mockMvc.perform(get("/api/v1/markets/{marketId}/orderbook", MARKET_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", is("urn:dex:problem:not-found")))
                .andExpect(jsonPath("$.title", is("Resource not found")))
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.detail", is("The requested API resource was not found.")))
                .andExpect(jsonPath("$.instance", is("/api/v1/markets/1/orderbook")))
                .andExpect(content().string(not(containsString(OTHER_DEPLOYMENT))));
    }

    @Test
    void returnsOnlyEligibleOrdersInPriceTimeOrderAsDecimalStrings() throws Exception {
        BigInteger largeOrderId = new BigInteger("9007199254740993");
        BigInteger largePrice = new BigInteger("1000000000000000000000000000000");
        BigInteger largeRemainder = new BigInteger("9007199254740995");
        BigInteger largeBlock = new BigInteger("9007199254740997");

        saveDeployment(CONFIGURED_DEPLOYMENT, 1);
        saveDeployment(OTHER_DEPLOYMENT, 2);
        saveMarket(CONFIGURED_DEPLOYMENT, MARKET_ID, false);
        saveMarket(CONFIGURED_DEPLOYMENT, BigInteger.TWO, true);
        saveMarket(OTHER_DEPLOYMENT, MARKET_ID, true);

        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, largeOrderId, OrderSide.BUY,
                largePrice, largeRemainder.add(BigInteger.ONE), largeRemainder,
                OrderStatus.PARTIALLY_FILLED, largeBlock, BigInteger.ZERO, BigInteger.ZERO, 1);
        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(3), OrderSide.BUY,
                BigInteger.valueOf(300), BigInteger.TEN, BigInteger.TEN,
                OrderStatus.OPEN, BigInteger.valueOf(4), BigInteger.ONE, BigInteger.ONE, 2);
        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(2), OrderSide.BUY,
                BigInteger.valueOf(300), BigInteger.TEN, BigInteger.TEN,
                OrderStatus.OPEN, BigInteger.valueOf(4), BigInteger.ONE, BigInteger.ONE, 3);
        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(4), OrderSide.BUY,
                BigInteger.valueOf(250), BigInteger.TEN, BigInteger.ZERO,
                OrderStatus.FILLED, BigInteger.valueOf(3), BigInteger.ZERO, BigInteger.ZERO, 4);

        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(10), OrderSide.SELL,
                BigInteger.valueOf(100), BigInteger.TEN, BigInteger.TEN,
                OrderStatus.OPEN, BigInteger.valueOf(8), BigInteger.ZERO, BigInteger.ONE, 5);
        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(11), OrderSide.SELL,
                BigInteger.valueOf(100), BigInteger.TEN, BigInteger.valueOf(4),
                OrderStatus.PARTIALLY_FILLED, BigInteger.valueOf(8), BigInteger.ONE, BigInteger.ZERO, 6);
        saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(12), OrderSide.SELL,
                BigInteger.valueOf(90), BigInteger.TEN, BigInteger.valueOf(5),
                OrderStatus.CANCELLED, BigInteger.valueOf(7), BigInteger.ZERO, BigInteger.ZERO, 7);

        saveOrder(CONFIGURED_DEPLOYMENT, BigInteger.TWO, BigInteger.valueOf(20), OrderSide.BUY,
                largePrice.add(BigInteger.ONE), BigInteger.TEN, BigInteger.TEN,
                OrderStatus.OPEN, BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO, 8);
        saveOrder(OTHER_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(21), OrderSide.SELL,
                BigInteger.ONE, BigInteger.TEN, BigInteger.TEN,
                OrderStatus.OPEN, BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO, 9);
        orderRepository.flush();

        mockMvc.perform(get("/api/v1/markets/{marketId}/orderbook", MARKET_ID).param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketId", is("1")))
                .andExpect(jsonPath("$.bids.items.length()", is(3)))
                .andExpect(jsonPath("$.bids.items[0].orderId", is(largeOrderId.toString())))
                .andExpect(jsonPath("$.bids.items[0].side", is("BUY")))
                .andExpect(jsonPath(
                        "$.bids.items[0].limitPriceQuoteAtomsPerLot",
                        is(largePrice.toString())
                ))
                .andExpect(jsonPath("$.bids.items[0].remainingLots", is(largeRemainder.toString())))
                .andExpect(jsonPath(
                        "$.bids.items[0].creationPosition.blockNumber",
                        is(largeBlock.toString())
                ))
                .andExpect(jsonPath("$.bids.items[0].creationPosition.transactionIndex", is("0")))
                .andExpect(jsonPath("$.bids.items[0].creationPosition.logIndex", is("0")))
                .andExpect(jsonPath("$.bids.items[1].orderId", is("2")))
                .andExpect(jsonPath("$.bids.items[2].orderId", is("3")))
                .andExpect(jsonPath("$.bids.hasMore", is(false)))
                .andExpect(jsonPath("$.asks.items.length()", is(2)))
                .andExpect(jsonPath("$.asks.items[0].orderId", is("10")))
                .andExpect(jsonPath("$.asks.items[1].orderId", is("11")))
                .andExpect(jsonPath("$.asks.hasMore", is(false)));
    }

    @Test
    void appliesLimitIndependentlyAndReportsMoreRowsPerSide() throws Exception {
        saveDeployment(CONFIGURED_DEPLOYMENT, 1);
        saveMarket(CONFIGURED_DEPLOYMENT, MARKET_ID, true);

        for (int index = 1; index <= 3; index++) {
            saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(index), OrderSide.BUY,
                    BigInteger.valueOf(400L - index), BigInteger.TEN, BigInteger.TEN,
                    OrderStatus.OPEN, BigInteger.valueOf(index), BigInteger.ZERO, BigInteger.ZERO, index);
            saveOrder(CONFIGURED_DEPLOYMENT, MARKET_ID, BigInteger.valueOf(index + 10L), OrderSide.SELL,
                    BigInteger.valueOf(100L + index), BigInteger.TEN, BigInteger.TEN,
                    OrderStatus.OPEN, BigInteger.valueOf(index), BigInteger.ZERO, BigInteger.ZERO, index + 10L);
        }
        orderRepository.flush();

        mockMvc.perform(get("/api/v1/markets/{marketId}/orderbook", MARKET_ID).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit", is(2)))
                .andExpect(jsonPath("$.bids.items.length()", is(2)))
                .andExpect(jsonPath("$.bids.items[0].orderId", is("1")))
                .andExpect(jsonPath("$.bids.items[1].orderId", is("2")))
                .andExpect(jsonPath("$.bids.hasMore", is(true)))
                .andExpect(jsonPath("$.asks.items.length()", is(2)))
                .andExpect(jsonPath("$.asks.items[0].orderId", is("11")))
                .andExpect(jsonPath("$.asks.items[1].orderId", is("12")))
                .andExpect(jsonPath("$.asks.hasMore", is(true)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "-1"})
    void rejectsInvalidMarketIds(String marketId) throws Exception {
        mockMvc.perform(get("/api/v1/markets/{marketId}/orderbook", marketId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", is("urn:dex:problem:invalid-request")))
                .andExpect(jsonPath("$.errors[0].field", is("marketId")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "0", "201"})
    void rejectsInvalidLimits(String limit) throws Exception {
        mockMvc.perform(get("/api/v1/markets/{marketId}/orderbook", MARKET_ID).param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", is("urn:dex:problem:invalid-request")))
                .andExpect(jsonPath("$.errors[0].field", is("limit")));
    }

    private void saveDeployment(String deploymentId, long identitySeed) {
        deploymentRepository.saveAndFlush(new ChainDeploymentEntity(
                deploymentId,
                BlockchainMode.MOCK,
                BigInteger.valueOf(identitySeed),
                address(identitySeed),
                BigInteger.ZERO,
                hash(identitySeed)
        ));
    }

    private void saveMarket(String deploymentId, BigInteger marketId, boolean active) {
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
    }

    private void saveOrder(
            String deploymentId,
            BigInteger marketId,
            BigInteger orderId,
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
                address(1_000 + identitySeed),
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
