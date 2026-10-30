package com.dex.backend.settlement;

import com.dex.backend.TestcontainersConfiguration;
import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.blockchain.execution.TransactionStatus;
import com.dex.backend.blockchain.mock.MockBlockchainFixtures;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.matcher.MatchProposal;
import com.dex.backend.order.Order;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.execution.ExecutionAttemptEntity;
import com.dex.backend.persistence.execution.ExecutionAttemptRepository;
import com.dex.backend.persistence.execution.ExecutionAttemptStatus;
import com.dex.backend.persistence.market.MarketEntity;
import com.dex.backend.persistence.market.MarketEntityId;
import com.dex.backend.persistence.market.MarketRepository;
import com.dex.backend.persistence.order.OrderEntity;
import com.dex.backend.persistence.order.OrderEntityId;
import com.dex.backend.persistence.order.OrderRepository;
import com.dex.backend.persistence.trade.TradeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SettlementExecutionIntegrationTest {

    private static final String DEPLOYMENT_ID = "integration-test";

    @Autowired
    private SettlementExecutionCoordinator coordinator;

    @Autowired
    private BlockchainGateway gateway;

    @Autowired
    private ExecutionAttemptRepository attemptRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private MarketRepository marketRepository;

    @Autowired
    private ChainDeploymentRepository deploymentRepository;

    @BeforeEach
    void prepareProjectionAndReservation() {
        clearProjectionAndReservations();

        deploymentRepository.saveAndFlush(new ChainDeploymentEntity(
                DEPLOYMENT_ID,
                BlockchainMode.MOCK,
                MockBlockchainFixtures.CHAIN_ID,
                MockBlockchainFixtures.DEX_ADDRESS,
                MockBlockchainFixtures.DEPLOYMENT_BLOCK,
                MockBlockchainFixtures.BLOCK_100.hash()
        ));
        marketRepository.saveAndFlush(new MarketEntity(
                new MarketEntityId(
                        DEPLOYMENT_ID,
                        MockBlockchainFixtures.MARKET_ID
                ),
                MockBlockchainFixtures.BASE_TOKEN_ADDRESS,
                MockBlockchainFixtures.QUOTE_TOKEN_ADDRESS,
                MockBlockchainFixtures.BASE_SYMBOL,
                MockBlockchainFixtures.QUOTE_SYMBOL,
                MockBlockchainFixtures.BASE_DECIMALS,
                MockBlockchainFixtures.QUOTE_DECIMALS,
                MockBlockchainFixtures.BASE_LOT_SIZE_ATOMS,
                true
        ));
        orderRepository.save(toEntity(MockBlockchainFixtures.ALICE_SELL_ORDER));
        orderRepository.save(toEntity(MockBlockchainFixtures.BOB_BUY_ORDER));
        orderRepository.flush();

        attemptRepository.saveAndFlush(ExecutionAttemptEntity.reserved(
                UUID.randomUUID(),
                new MatchProposal(
                        DEPLOYMENT_ID,
                        MockBlockchainFixtures.MARKET_ID,
                        MockBlockchainFixtures.BOB_ORDER_ID,
                        MockBlockchainFixtures.ALICE_ORDER_ID,
                        MockBlockchainFixtures.BOB_LOTS,
                        MockBlockchainFixtures.ALICE_LIMIT_PRICE
                ),
                MockBlockchainFixtures.EXECUTOR_ADDRESS,
                Instant.now()
        ));
    }

    @AfterEach
    void clearProjectionAndReservations() {
        attemptRepository.deleteAll();
        tradeRepository.deleteAll();
        orderRepository.deleteAll();
        marketRepository.deleteAll();
        deploymentRepository.deleteAll();
    }

    @Test
    void separateCyclesSignThenSubmitWithoutChangingProjections() {
        BigInteger nonceBefore = gateway.getPendingExecutorNonce();

        assertEquals(
                SettlementExecutorCycleStatus.SIGNED,
                coordinator.runCycle()
        );

        ExecutionAttemptEntity attempt = attemptRepository.findAll().getFirst();
        OrderEntity buy = orderRepository.findById(new OrderEntityId(
                DEPLOYMENT_ID,
                MockBlockchainFixtures.BOB_ORDER_ID
        )).orElseThrow();
        OrderEntity sell = orderRepository.findById(new OrderEntityId(
                DEPLOYMENT_ID,
                MockBlockchainFixtures.ALICE_ORDER_ID
        )).orElseThrow();

        assertAll(
                () -> assertEquals(
                        ExecutionAttemptStatus.SIGNED,
                        attempt.getStatus()
                ),
                () -> assertEquals(BigInteger.ZERO, attempt.getExecutorNonce()),
                () -> assertNotNull(attempt.getTransactionHash()),
                () -> assertNotNull(attempt.toPreparedSettlement()),
                () -> assertEquals(
                        TransactionStatus.NOT_FOUND,
                        gateway.getTransaction(attempt.getTransactionHash()).status()
                ),
                () -> assertEquals(2, orderRepository.count()),
                () -> assertEquals(0, tradeRepository.count()),
                () -> assertEquals(
                        MockBlockchainFixtures.BOB_LOTS,
                        buy.getRemainingLots()
                ),
                () -> assertEquals(
                        MockBlockchainFixtures.ALICE_LOTS,
                        sell.getRemainingLots()
                )
        );

        assertEquals(
                SettlementExecutorCycleStatus.WAITING_FOR_RECONCILIATION,
                coordinator.runCycle()
        );

        ExecutionAttemptEntity submitted = attemptRepository.findAll()
                .getFirst();
        BigInteger nonceAfterSubmission = gateway.getPendingExecutorNonce();

        assertAll(
                () -> assertEquals(
                        ExecutionAttemptStatus.SUBMITTED,
                        submitted.getStatus()
                ),
                () -> assertNotNull(submitted.getSubmittedAt()),
                () -> assertEquals(
                        TransactionStatus.PENDING,
                        gateway.getTransaction(
                                submitted.getTransactionHash()
                        ).status()
                ),
                () -> assertEquals(
                        nonceBefore.add(BigInteger.ONE),
                        nonceAfterSubmission
                ),
                () -> assertEquals(2, orderRepository.count()),
                () -> assertEquals(0, tradeRepository.count())
        );

        assertEquals(
                SettlementExecutorCycleStatus.WAITING_FOR_RECONCILIATION,
                coordinator.runCycle()
        );
        assertEquals(nonceAfterSubmission, gateway.getPendingExecutorNonce());
    }

    @Test
    void acceptedBroadcastRecoversWhenDatabaseStillSaysSigned() {
        assertEquals(
                SettlementExecutorCycleStatus.SIGNED,
                coordinator.runCycle()
        );
        ExecutionAttemptEntity signed = attemptRepository.findAll().getFirst();
        var prepared = signed.toPreparedSettlement();

        assertEquals(
                prepared.transactionHash(),
                gateway.broadcastSettlement(prepared)
        );
        BigInteger nonceAfterAcceptedBroadcast =
                gateway.getPendingExecutorNonce();
        assertEquals(
                ExecutionAttemptStatus.SIGNED,
                attemptRepository.findById(signed.getAttemptId())
                        .orElseThrow()
                        .getStatus()
        );

        assertEquals(
                SettlementExecutorCycleStatus.WAITING_FOR_RECONCILIATION,
                coordinator.runCycle()
        );

        ExecutionAttemptEntity recovered = attemptRepository
                .findById(signed.getAttemptId())
                .orElseThrow();
        assertAll(
                () -> assertEquals(
                        ExecutionAttemptStatus.SUBMITTED,
                        recovered.getStatus()
                ),
                () -> assertEquals(
                        prepared,
                        recovered.toPreparedSettlement()
                ),
                () -> assertEquals(
                        nonceAfterAcceptedBroadcast,
                        gateway.getPendingExecutorNonce()
                ),
                () -> assertEquals(2, orderRepository.count()),
                () -> assertEquals(0, tradeRepository.count())
        );
    }

    private static OrderEntity toEntity(Order order) {
        return new OrderEntity(
                new OrderEntityId(DEPLOYMENT_ID, order.orderId()),
                order.marketId(),
                order.owner(),
                order.side(),
                order.limitPriceQuoteAtomsPerLot(),
                order.originalLots(),
                order.remainingLots(),
                order.status(),
                order.creationPosition().blockNumber(),
                order.creationPosition().transactionIndex(),
                order.creationPosition().logIndex(),
                order.creationTransactionHash(),
                order.creationBlockHash(),
                order.blockTimestamp()
        );
    }
}
