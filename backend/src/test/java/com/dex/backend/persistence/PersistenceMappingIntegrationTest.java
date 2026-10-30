package com.dex.backend.persistence;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.blockchain.event.DexEventType;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.event.ProcessedEventEntity;
import com.dex.backend.persistence.event.ProcessedEventEntityId;
import com.dex.backend.persistence.event.ProcessedEventRepository;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import com.dex.backend.persistence.market.MarketEntity;
import com.dex.backend.persistence.market.MarketEntityId;
import com.dex.backend.persistence.market.MarketRepository;
import com.dex.backend.persistence.order.OrderEntity;
import com.dex.backend.persistence.order.OrderEntityId;
import com.dex.backend.persistence.order.OrderRepository;
import com.dex.backend.persistence.trade.TradeEntity;
import com.dex.backend.persistence.trade.TradeEntityId;
import com.dex.backend.persistence.trade.TradeRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class PersistenceMappingIntegrationTest {

    private static final Instant BLOCK_TIME = Instant.parse("2026-01-02T03:04:05Z");

    @Autowired
    private ChainDeploymentRepository deploymentRepository;

    @Autowired
    private MarketRepository marketRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private IndexerCheckpointRepository checkpointRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsAndReloadsCompleteProjectionGraph() {
        String deploymentId = "mapping-graph";
        EthereumAddress dexAddress = address("ABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD");
        Hash256 deploymentHash = hash('A');
        BigInteger marketId = BigInteger.ONE;
        BigInteger sellOrderId = BigInteger.valueOf(11);
        BigInteger buyOrderId = BigInteger.valueOf(12);
        Hash256 tradeTransactionHash = hash('E');

        ChainDeploymentEntity deployment = new ChainDeploymentEntity(
                deploymentId,
                BlockchainMode.ETHEREUM,
                BigInteger.valueOf(31_337),
                dexAddress,
                address("9".repeat(40)),
                BigInteger.valueOf(100),
                deploymentHash
        );
        MarketEntity market = new MarketEntity(
                new MarketEntityId(deploymentId, marketId),
                address("1111111111111111111111111111111111111111"),
                address("2222222222222222222222222222222222222222"),
                "BASE",
                "QUOTE",
                18,
                6,
                new BigInteger("1000000000000000000"),
                true
        );
        OrderEntity sellOrder = order(
                deploymentId,
                sellOrderId,
                marketId,
                address("3333333333333333333333333333333333333333"),
                OrderSide.SELL,
                BigInteger.valueOf(100),
                BigInteger.valueOf(60),
                OrderStatus.PARTIALLY_FILLED,
                BigInteger.ZERO,
                hash('B')
        );
        OrderEntity buyOrder = order(
                deploymentId,
                buyOrderId,
                marketId,
                address("4444444444444444444444444444444444444444"),
                OrderSide.BUY,
                BigInteger.valueOf(40),
                BigInteger.ZERO,
                OrderStatus.FILLED,
                BigInteger.ONE,
                hash('C')
        );
        ProcessedEventEntityId eventId = new ProcessedEventEntityId(
                deploymentId,
                tradeTransactionHash,
                BigInteger.valueOf(2)
        );
        ProcessedEventEntity processedEvent = new ProcessedEventEntity(
                eventId,
                dexAddress,
                DexEventType.TRADE_EXECUTED,
                BigInteger.valueOf(104),
                hash('F'),
                BigInteger.valueOf(3)
        );
        TradeEntityId tradeId = new TradeEntityId(
                deploymentId,
                tradeTransactionHash,
                BigInteger.valueOf(2)
        );
        TradeEntity trade = new TradeEntity(
                tradeId,
                BigInteger.valueOf(501),
                marketId,
                buyOrderId,
                sellOrderId,
                BigInteger.valueOf(40),
                BigInteger.TEN,
                BigInteger.ZERO,
                BigInteger.valueOf(60),
                BigInteger.valueOf(104),
                BigInteger.valueOf(3),
                hash('F'),
                BLOCK_TIME
        );
        IndexerCheckpointEntity checkpoint = new IndexerCheckpointEntity(
                deploymentId,
                BigInteger.valueOf(104),
                hash('F'),
                BigInteger.valueOf(105),
                hash('9'),
                BLOCK_TIME.plusSeconds(5),
                null,
                null,
                false,
                null
        );

        deploymentRepository.save(deployment);
        marketRepository.save(market);
        orderRepository.save(sellOrder);
        orderRepository.save(buyOrder);
        processedEventRepository.save(processedEvent);
        tradeRepository.save(trade);
        checkpointRepository.save(checkpoint);
        entityManager.flush();
        entityManager.clear();

        ChainDeploymentEntity reloadedDeployment = deploymentRepository.findById(deploymentId).orElseThrow();
        MarketEntity reloadedMarket = marketRepository.findById(market.getId()).orElseThrow();
        OrderEntity reloadedBuyOrder = orderRepository.findById(buyOrder.getId()).orElseThrow();
        ProcessedEventEntity reloadedEvent = processedEventRepository.findById(eventId).orElseThrow();
        TradeEntity reloadedTrade = tradeRepository.findById(tradeId).orElseThrow();
        IndexerCheckpointEntity reloadedCheckpoint = checkpointRepository.findById(deploymentId).orElseThrow();

        assertEquals(dexAddress, reloadedDeployment.getDexAddress());
        assertEquals(deploymentHash, reloadedDeployment.getDeploymentBlockHash());
        assertEquals(new BigInteger("1000000000000000000"), reloadedMarket.getBaseLotSizeAtoms());
        assertEquals(OrderSide.BUY, reloadedBuyOrder.getSide());
        assertEquals(OrderStatus.FILLED, reloadedBuyOrder.getStatus());
        assertEquals(DexEventType.TRADE_EXECUTED, reloadedEvent.getEventName());
        assertEquals(BigInteger.valueOf(40), reloadedTrade.getFillLots());
        assertEquals(BigInteger.valueOf(104), reloadedCheckpoint.getLastProcessedBlockNumber());
        assertTrue(processedEventRepository.existsById(eventId));

        assertNotNull(reloadedDeployment.getCreatedAt());
        assertNotNull(reloadedMarket.getCreatedAt());
        assertNotNull(reloadedMarket.getUpdatedAt());
        assertNotNull(reloadedBuyOrder.getProjectionUpdatedAt());
        assertNotNull(reloadedEvent.getProcessedAt());
        assertNotNull(reloadedTrade.getProjectionCreatedAt());
        assertNotNull(reloadedCheckpoint.getUpdatedAt());

        assertEquals(
                "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                jdbcTemplate.queryForObject(
                        "SELECT dex_address FROM chain_deployments WHERE deployment_id = ?",
                        String.class,
                        deploymentId
                )
        );
        assertEquals(
                "0x" + "a".repeat(64),
                jdbcTemplate.queryForObject(
                        "SELECT deployment_block_hash FROM chain_deployments WHERE deployment_id = ?",
                        String.class,
                        deploymentId
                )
        );
        assertEquals(
                "ETHEREUM",
                jdbcTemplate.queryForObject(
                        "SELECT adapter_mode FROM chain_deployments WHERE deployment_id = ?",
                        String.class,
                        deploymentId
                )
        );
        assertEquals(
                "TRADE_EXECUTED",
                jdbcTemplate.queryForObject(
                        "SELECT event_name FROM processed_events WHERE deployment_id = ?",
                        String.class,
                        deploymentId
                )
        );
    }

    @Test
    void preservesLargeIntegersAndScopesCompositeIdsByDeployment() {
        BigInteger largeValue = new BigInteger("9".repeat(70));
        BigInteger sharedMarketId = largeValue.subtract(BigInteger.ONE);

        deploymentRepository.save(deployment("scope-a", largeValue, '1', "10"));
        deploymentRepository.save(deployment("scope-b", largeValue.add(BigInteger.ONE), '2', "20"));
        marketRepository.save(market("scope-a", sharedMarketId, largeValue, "11", "12"));
        marketRepository.save(market("scope-b", sharedMarketId, largeValue, "21", "22"));
        entityManager.flush();
        entityManager.clear();

        MarketEntity first = marketRepository.findById(new MarketEntityId("scope-a", sharedMarketId)).orElseThrow();
        MarketEntity second = marketRepository.findById(new MarketEntityId("scope-b", sharedMarketId)).orElseThrow();

        assertEquals(largeValue, first.getBaseLotSizeAtoms());
        assertEquals(sharedMarketId, second.getId().getMarketId());
        assertEquals(2, marketRepository.count());
    }

    private static OrderEntity order(
            String deploymentId,
            BigInteger orderId,
            BigInteger marketId,
            EthereumAddress owner,
            OrderSide side,
            BigInteger originalLots,
            BigInteger remainingLots,
            OrderStatus status,
            BigInteger creationLogIndex,
            Hash256 creationTransactionHash
    ) {
        return new OrderEntity(
                new OrderEntityId(deploymentId, orderId),
                marketId,
                owner,
                side,
                BigInteger.TEN,
                originalLots,
                remainingLots,
                status,
                BigInteger.valueOf(101),
                BigInteger.ZERO,
                creationLogIndex,
                creationTransactionHash,
                hash('D'),
                BLOCK_TIME
        );
    }

    private static ChainDeploymentEntity deployment(
            String deploymentId,
            BigInteger chainId,
            char hashCharacter,
            String addressPrefix
    ) {
        return new ChainDeploymentEntity(
                deploymentId,
                BlockchainMode.MOCK,
                chainId,
                address(addressPrefix.repeat(20)),
                address(addressPrefix.repeat(20)),
                BigInteger.ZERO,
                hash(hashCharacter)
        );
    }

    private static MarketEntity market(
            String deploymentId,
            BigInteger marketId,
            BigInteger lotSize,
            String basePrefix,
            String quotePrefix
    ) {
        return new MarketEntity(
                new MarketEntityId(deploymentId, marketId),
                address(basePrefix.repeat(20)),
                address(quotePrefix.repeat(20)),
                "BASE",
                "QUOTE",
                18,
                18,
                lotSize,
                true
        );
    }

    private static EthereumAddress address(String hexadecimalBody) {
        return new EthereumAddress("0x" + hexadecimalBody);
    }

    private static Hash256 hash(char hexadecimalCharacter) {
        return new Hash256("0x" + String.valueOf(hexadecimalCharacter).repeat(64));
    }
}
