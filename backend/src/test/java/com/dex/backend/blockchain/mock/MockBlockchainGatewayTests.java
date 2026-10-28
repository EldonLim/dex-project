package com.dex.backend.blockchain.mock;

import com.dex.backend.blockchain.event.DexEvent;
import com.dex.backend.blockchain.event.DexEventType;
import com.dex.backend.blockchain.model.BlockRange;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.order.OrderSide;
import org.junit.jupiter.api.Test;
import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.blockchain.event.TradeExecutedEvent;
import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.SettlementRequest;
import com.dex.backend.blockchain.execution.TransactionObservation;
import com.dex.backend.blockchain.execution.TransactionStatus;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.blockchain.model.OrderSnapshot;
import com.dex.backend.order.OrderStatus;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockBlockchainGatewayTests {

    private static final String DEPLOYMENT = "test-deployment";
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger NEGATIVE = BigInteger.valueOf(-1);

    @Test
    void initializesIdentityAndLatestBlock() {
        MockBlockchainGateway gateway = gateway();

        assertAll(
                () -> assertEquals(
                        DEPLOYMENT, gateway.getChainIdentity().deploymentId()
                ),
                () -> assertEquals(
                        MockBlockchainFixtures.CHAIN_ID,
                        gateway.getChainIdentity().chainId()
                ),
                () -> assertEquals(
                        MockBlockchainFixtures.DEX_ADDRESS,
                        gateway.getChainIdentity().dexAddress()
                ),
                () -> assertEquals(
                        MockBlockchainFixtures.DEPLOYMENT_BLOCK,
                        gateway.getChainIdentity().deploymentBlockNumber()
                ),
                () -> assertEquals(
                        MockBlockchainFixtures.BLOCK_100.hash(),
                        gateway.getChainIdentity().deploymentBlockHash()
                ),
                () -> assertEquals(
                        MockBlockchainFixtures.BLOCK_103,
                        gateway.getLatestBlock()
                ),
                () -> assertEquals(
                        MockBlockchainFixtures.EXECUTOR_ADDRESS,
                        gateway.getExecutorAddress()
                ),
                () -> assertEquals(ZERO, gateway.getPendingExecutorNonce())
        );
    }

    @Test
    void rejectsInvalidDeploymentIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MockBlockchainGateway(null)
        );

        for (String id : List.of("", " \t\n", "d".repeat(129))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new MockBlockchainGateway(id)
            );
        }

        assertEquals(
                "d".repeat(128),
                new MockBlockchainGateway("d".repeat(128))
                        .getChainIdentity().deploymentId()
        );
    }

    @Test
    void retrievesBlocksAndReturnsEmptyForMissingBlocks() {
        MockBlockchainGateway gateway = gateway();

        assertEquals(
                MockBlockchainFixtures.BLOCK_102,
                gateway.getBlock(BigInteger.valueOf(102)).orElseThrow()
        );

        assertTrue(gateway.getBlock(ZERO).isEmpty());
        assertTrue(gateway.getBlock(BigInteger.valueOf(104)).isEmpty());
    }

    @Test
    void initializesMarketAndBothOrdersWithSuppliedDeploymentId() {
        MockBlockchainGateway gateway = gateway();

        var market = gateway.getMarket(ONE).orElseThrow();
        var alice = gateway.getOrder(ONE).orElseThrow();
        var bob = gateway.getOrder(BigInteger.TWO).orElseThrow();

        assertAll(
                () -> assertEquals(DEPLOYMENT, market.deploymentId()),
                () -> assertTrue(market.active()),
                () -> assertEquals(
                        MockBlockchainFixtures.BASE_TOKEN_ADDRESS,
                        market.baseTokenAddress()
                ),
                () -> assertEquals(
                        MockBlockchainFixtures.QUOTE_TOKEN_ADDRESS,
                        market.quoteTokenAddress()
                ),
                () -> assertEquals(DEPLOYMENT, alice.deploymentId()),
                () -> assertEquals(OrderSide.SELL, alice.side()),
                () -> assertEquals(
                        MockBlockchainFixtures.ALICE_ADDRESS, alice.owner()
                ),
                () -> assertEquals(
                        BigInteger.TEN, alice.limitPriceQuoteAtomsPerLot()
                ),
                () -> assertEquals(
                        BigInteger.valueOf(100), alice.remainingLots()
                ),
                () -> assertEquals(DEPLOYMENT, bob.deploymentId()),
                () -> assertEquals(OrderSide.BUY, bob.side()),
                () -> assertEquals(
                        MockBlockchainFixtures.BOB_ADDRESS, bob.owner()
                ),
                () -> assertEquals(
                        BigInteger.valueOf(12), bob.limitPriceQuoteAtomsPerLot()
                ),
                () -> assertEquals(
                        BigInteger.valueOf(40), bob.remainingLots()
                ),
                () -> assertTrue(alice.isMatchable()),
                () -> assertTrue(bob.isMatchable())
        );
    }

    @Test
    void missingMarketsAndOrdersReturnEmptyOptionals() {
        MockBlockchainGateway gateway = gateway();

        assertTrue(gateway.getMarket(BigInteger.valueOf(999)).isEmpty());
        assertTrue(gateway.getOrder(BigInteger.valueOf(999)).isEmpty());
    }

    @Test
    void returnsEventsInAscendingPositionOrder() {
        List<DexEvent> events = gateway().getEvents(range(100, 103));

        assertEquals(
                List.of(
                        DexEventType.MARKET_REGISTERED,
                        DexEventType.ORDER_PLACED,
                        DexEventType.ORDER_PLACED
                ),
                events.stream().map(DexEvent::type).toList()
        );

        assertEquals(
                List.of(
                        BigInteger.valueOf(101),
                        BigInteger.valueOf(102),
                        BigInteger.valueOf(103)
                ),
                events.stream()
                        .map(event -> event.metadata().position().blockNumber())
                        .toList()
        );

        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i - 1).compareTo(events.get(i)) < 0);
        }

        assertTrue(events.stream().allMatch(
                event -> DEPLOYMENT.equals(event.metadata().deploymentId())
        ));
    }

    @Test
    void includesBothRangeBoundaries() {
        MockBlockchainGateway gateway = gateway();

        List<DexEvent> events = gateway.getEvents(range(102, 103));

        assertEquals(2, events.size());
        assertEquals(
                BigInteger.valueOf(102),
                events.get(0).metadata().position().blockNumber()
        );
        assertEquals(
                BigInteger.valueOf(103),
                events.get(1).metadata().position().blockNumber()
        );

        List<DexEvent> singleBlock = gateway.getEvents(range(102, 102));

        assertEquals(1, singleBlock.size());
        assertEquals(
                BigInteger.valueOf(102),
                singleBlock.get(0).metadata().position().blockNumber()
        );
    }

    @Test
    void eventResultsAreImmutable() {
        MockBlockchainGateway gateway = gateway();

        List<DexEvent> events = gateway.getEvents(range(100, 103));
        DexEvent first = events.get(0);

        assertAll(
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> events.add(first)
                ),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> events.remove(0)
                ),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> events.set(0, first)
                )
        );

        assertEquals(3, gateway.getEvents(range(100, 103)).size());
    }

    @Test
    void emptyEventResultsAreImmutableAndNonNull() {
        MockBlockchainGateway gateway = gateway();

        List<DexEvent> empty = gateway.getEvents(range(104, 110));
        DexEvent event = gateway.getEvents(range(101, 101)).get(0);

        assertNotNull(empty);
        assertTrue(empty.isEmpty());

        assertThrows(
                UnsupportedOperationException.class,
                () -> empty.add(event)
        );
    }

    @Test
    void unknownBalancesAndAllowancesAreZero() {
        MockBlockchainGateway gateway = gateway();

        EthereumAddress unknownToken =
                new EthereumAddress("0x" + "9".repeat(40));

        assertAll(
                () -> assertEquals(
                        ZERO,
                        gateway.getTokenBalance(
                                unknownToken,
                                MockBlockchainFixtures.ALICE_ADDRESS
                        )
                ),
                () -> assertEquals(
                        ZERO,
                        gateway.getTokenAllowance(
                                unknownToken,
                                MockBlockchainFixtures.ALICE_ADDRESS,
                                MockBlockchainFixtures.DEX_ADDRESS
                        )
                )
        );
    }

    @Test
    void rejectsNullOrNegativeQueryIds() {
        MockBlockchainGateway gateway = gateway();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.getBlock(null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.getBlock(NEGATIVE)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.getMarket(null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.getMarket(NEGATIVE)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.getOrder(null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.getOrder(NEGATIVE)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.getEvents(null)
                )
        );
    }

    @Test
    void rejectsNullBalanceAndAllowanceArguments() {
        MockBlockchainGateway gateway = gateway();

        EthereumAddress token = MockBlockchainFixtures.BASE_TOKEN_ADDRESS;
        EthereumAddress owner = MockBlockchainFixtures.ALICE_ADDRESS;
        EthereumAddress spender = MockBlockchainFixtures.DEX_ADDRESS;

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.getTokenBalance(null, owner)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.getTokenBalance(token, null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.getTokenAllowance(null, owner, spender)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.getTokenAllowance(token, null, spender)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.getTokenAllowance(token, owner, null)
                )
        );
    }

    @Test
    void independentlyConstructedGatewaysStartIdentically() {
        MockBlockchainGateway first = gateway();
        MockBlockchainGateway second = gateway();

        assertAll(
                () -> assertEquals(
                        first.getChainIdentity(), second.getChainIdentity()
                ),
                () -> assertEquals(
                        first.getLatestBlock(), second.getLatestBlock()
                ),
                () -> assertEquals(
                        first.getEvents(range(100, 103)),
                        second.getEvents(range(100, 103))
                ),
                () -> assertEquals(first.getMarket(ONE), second.getMarket(ONE)),
                () -> assertEquals(first.getOrder(ONE), second.getOrder(ONE)),
                () -> assertEquals(
                        first.getOrder(BigInteger.TWO),
                        second.getOrder(BigInteger.TWO)
                ),
                () -> assertEquals(
                        first.getPendingExecutorNonce(),
                        second.getPendingExecutorNonce()
                )
        );
    }

    @Test
    void suppliedDeploymentDoesNotModifyStandardFixtures() {
        MockBlockchainGateway first =
                new MockBlockchainGateway("deployment-a");

        MockBlockchainGateway second =
                new MockBlockchainGateway("deployment-b");

        assertEquals(
                "deployment-a",
                first.getOrder(ONE).orElseThrow().deploymentId()
        );
        assertEquals(
                "deployment-b",
                second.getOrder(ONE).orElseThrow().deploymentId()
        );
        assertEquals(
                MockBlockchainFixtures.DEPLOYMENT_ID,
                MockBlockchainFixtures.ALICE_SELL_ORDER_SNAPSHOT.deploymentId()
        );
    }

    private static MockBlockchainGateway gateway() {
        return new MockBlockchainGateway(DEPLOYMENT);
    }

    private static BlockRange range(long from, long to) {
        return new BlockRange(
                BigInteger.valueOf(from),
                BigInteger.valueOf(to)
        );
    }

    @Test
    void preparationDoesNotChangePublishedState() {
        MockBlockchainGateway gateway = gateway();
        PublishedState before = publishedState(gateway);

        PreparedSettlement first = gateway.prepareSettlement(standardRequest());

        assertEquals(before, publishedState(gateway));
        assertEquals(ZERO, first.nonce());
        assertEquals(
                TransactionStatus.NOT_FOUND,
                gateway.getTransaction(first.transactionHash()).status()
        );

        PreparedSettlement second = gateway.prepareSettlement(standardRequest());

        assertEquals(before, publishedState(gateway));
        assertEquals(ZERO, second.nonce());
        assertNotEquals(first.transactionHash(), second.transactionHash());
    }

    @Test
    void broadcastOnlyChangesTransactionStateAndNonce() {
        MockBlockchainGateway gateway = gateway();
        PreparedSettlement prepared =
                gateway.prepareSettlement(standardRequest());

        PublishedState before = publishedState(gateway);

        assertEquals(
                prepared.transactionHash(),
                gateway.broadcastSettlement(prepared)
        );

        PublishedState after = publishedState(gateway);

        assertAll(
                () -> assertEquals(before.latestBlock(), after.latestBlock()),
                () -> assertEquals(before.alice(), after.alice()),
                () -> assertEquals(before.bob(), after.bob()),
                () -> assertEquals(before.events(), after.events()),
                () -> assertEquals(before.ledger(), after.ledger()),
                () -> assertEquals(ONE, after.nonce()),
                () -> assertEquals(
                        TransactionStatus.PENDING,
                        gateway.getTransaction(prepared.transactionHash()).status()
                )
        );
    }

    @Test
    void rebroadcastIsIdempotentBeforeAndAfterMining() {
        MockBlockchainGateway gateway = gateway();
        PreparedSettlement prepared =
                gateway.prepareSettlement(standardRequest());

        gateway.broadcastSettlement(prepared);

        PublishedState pendingState = publishedState(gateway);
        TransactionObservation pending =
                gateway.getTransaction(prepared.transactionHash());

        assertEquals(
                prepared.transactionHash(),
                gateway.broadcastSettlement(prepared)
        );
        assertEquals(pendingState, publishedState(gateway));
        assertEquals(pending, gateway.getTransaction(prepared.transactionHash()));

        gateway.mineSuccessfulSettlement(prepared.transactionHash());

        PublishedState minedState = publishedState(gateway);
        TransactionObservation mined =
                gateway.getTransaction(prepared.transactionHash());

        assertEquals(
                prepared.transactionHash(),
                gateway.broadcastSettlement(prepared)
        );
        assertEquals(minedState, publishedState(gateway));
        assertEquals(mined, gateway.getTransaction(prepared.transactionHash()));
    }

    @Test
    void stalePreparedNonceIsRejectedWithoutMutation() {
        MockBlockchainGateway gateway = gateway();

        PreparedSettlement first =
                gateway.prepareSettlement(standardRequest());
        PreparedSettlement stale =
                gateway.prepareSettlement(standardRequest());

        gateway.broadcastSettlement(first);
        PublishedState before = publishedState(gateway);

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.broadcastSettlement(stale)
        );

        assertEquals(before, publishedState(gateway));
        assertEquals(
                TransactionStatus.NOT_FOUND,
                gateway.getTransaction(stale.transactionHash()).status()
        );
    }

    @Test
    void broadcastRejectsUnknownOrAlteredPreparedTransaction() {
        MockBlockchainGateway gateway = gateway();
        PreparedSettlement prepared =
                gateway.prepareSettlement(standardRequest());

        PreparedSettlement altered = new PreparedSettlement(
                prepared.request(),
                prepared.executorAddress(),
                prepared.nonce(),
                prepared.transactionHash(),
                prepared.signedTransactionHex() + "00"
        );

        PreparedSettlement unknown = new PreparedSettlement(
                prepared.request(),
                prepared.executorAddress(),
                prepared.nonce(),
                MockBlockchainFixtures.hash(BigInteger.valueOf(99999)),
                prepared.signedTransactionHex()
        );

        PublishedState before = publishedState(gateway);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.broadcastSettlement(null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.broadcastSettlement(altered)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.broadcastSettlement(unknown)
                )
        );

        assertEquals(before, publishedState(gateway));
        assertEquals(
                TransactionStatus.NOT_FOUND,
                gateway.getTransaction(prepared.transactionHash()).status()
        );
    }

    @Test
    void successfulMiningUpdatesOrdersLedgerAndEmitsOneTrade() {
        MockBlockchainGateway gateway = gateway();
        PreparedSettlement prepared =
                gateway.prepareSettlement(standardRequest());

        gateway.broadcastSettlement(prepared);

        BlockReference block =
                gateway.mineSuccessfulSettlement(prepared.transactionHash());

        OrderSnapshot alice = gateway.getOrder(ONE).orElseThrow();
        OrderSnapshot bob = gateway.getOrder(BigInteger.TWO).orElseThrow();

        List<TradeExecutedEvent> trades = gateway
                .getEvents(range(100, 104))
                .stream()
                .filter(TradeExecutedEvent.class::isInstance)
                .map(TradeExecutedEvent.class::cast)
                .toList();

        assertEquals(1, trades.size());
        TradeExecutedEvent trade = trades.get(0);

        assertAll(
                () -> assertEquals(BigInteger.valueOf(104), block.number()),
                () -> assertEquals(
                        MockBlockchainFixtures.BLOCK_103.timestamp().plusSeconds(12),
                        block.timestamp()
                ),
                () -> assertEquals(
                        MockBlockchainFixtures.hash(block.number()), block.hash()
                ),
                () -> assertEquals(BigInteger.TEN,
                        trade.executionPriceQuoteAtomsPerLot()),
                () -> assertEquals(BigInteger.valueOf(40), trade.fillLots()),
                () -> assertEquals(ONE, trade.tradeId()),
                () -> assertEquals(prepared.transactionHash(),
                        trade.metadata().transactionHash()),
                () -> assertEquals(block.hash(), trade.metadata().blockHash()),
                () -> assertEquals(ZERO,
                        trade.metadata().position().transactionIndex()),
                () -> assertEquals(ZERO, trade.metadata().position().logIndex()),
                () -> assertEquals(BigInteger.valueOf(60), alice.remainingLots()),
                () -> assertEquals(OrderStatus.PARTIALLY_FILLED, alice.status()),
                () -> assertEquals(ZERO, bob.remainingLots()),
                () -> assertEquals(OrderStatus.FILLED, bob.status()),
                () -> assertEquals(alice.remainingLots(),
                        trade.sellOrderRemainingLots()),
                () -> assertEquals(bob.remainingLots(),
                        trade.buyOrderRemainingLots()),
                () -> assertEquals(ONE, gateway.getPendingExecutorNonce()),
                () -> assertEquals(4, gateway.getEvents(range(100, 104)).size())
        );

        assertEquals(
                List.of(
                        BigInteger.valueOf(60000), // Alice base
                        BigInteger.valueOf(40000), // Bob base
                        BigInteger.valueOf(400),   // Alice quote
                        BigInteger.valueOf(80),    // Bob quote
                        BigInteger.valueOf(60000), // Alice base allowance
                        BigInteger.valueOf(80)     // Bob quote allowance
                ),
                ledger(gateway)
        );

        TransactionObservation observation =
                gateway.getTransaction(prepared.transactionHash());

        assertEquals(TransactionStatus.SUCCEEDED, observation.status());
        assertEquals(block.number(), observation.blockNumber());
        assertEquals(block.hash(), observation.blockHash());
        assertNull(observation.failureReason());

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.mineSuccessfulSettlement(prepared.transactionHash())
        );
    }

    @Test
    void revertedMiningPreservesOrdersLedgerAndEvents() {
        MockBlockchainGateway gateway = gateway();
        PreparedSettlement prepared =
                gateway.prepareSettlement(standardRequest());

        gateway.broadcastSettlement(prepared);
        PublishedState before = publishedState(gateway);

        BlockReference block = gateway.mineRevertedSettlement(
                prepared.transactionHash(),
                "Mock contract rejected settlement"
        );

        PublishedState after = publishedState(gateway);
        TransactionObservation observation =
                gateway.getTransaction(prepared.transactionHash());

        assertAll(
                () -> assertEquals(before.alice(), after.alice()),
                () -> assertEquals(before.bob(), after.bob()),
                () -> assertEquals(before.events(), after.events()),
                () -> assertEquals(before.ledger(), after.ledger()),
                () -> assertEquals(before.nonce(), after.nonce()),
                () -> assertEquals(BigInteger.valueOf(104), block.number()),
                () -> assertEquals(TransactionStatus.REVERTED, observation.status()),
                () -> assertEquals(block.number(), observation.blockNumber()),
                () -> assertEquals(block.hash(), observation.blockHash()),
                () -> assertEquals(
                        "Mock contract rejected settlement",
                        observation.failureReason()
                )
        );
    }

    @Test
    void blankRevertReasonBecomesAbsent() {
        MockBlockchainGateway gateway = gateway();
        PreparedSettlement prepared =
                gateway.prepareSettlement(standardRequest());

        gateway.broadcastSettlement(prepared);
        gateway.mineRevertedSettlement(prepared.transactionHash(), " \t\n");

        assertNull(
                gateway.getTransaction(prepared.transactionHash()).failureReason()
        );
    }

    @Test
    void transactionLookupNeverAdvancesState() {
        MockBlockchainGateway gateway = gateway();
        PreparedSettlement prepared =
                gateway.prepareSettlement(standardRequest());

        gateway.broadcastSettlement(prepared);
        PublishedState before = publishedState(gateway);

        for (int i = 0; i < 5; i++) {
            assertEquals(
                    TransactionStatus.PENDING,
                    gateway.getTransaction(prepared.transactionHash()).status()
            );
        }

        assertEquals(before, publishedState(gateway));
    }

    @Test
    void onlyPendingTransactionsMayBeMined() {
        MockBlockchainGateway gateway = gateway();
        PreparedSettlement prepared =
                gateway.prepareSettlement(standardRequest());

        PublishedState before = publishedState(gateway);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.mineSuccessfulSettlement(
                                prepared.transactionHash()
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> gateway.mineRevertedSettlement(
                                prepared.transactionHash(), "Failure"
                        )
                )
        );

        assertEquals(before, publishedState(gateway));
    }

    @Test
    void invalidSettlementRequestsFailWithoutMutation() {
        List<SettlementRequest> invalidRequests = List.of(
                new SettlementRequest(
                        "wrong-deployment", ONE, BigInteger.TWO, ONE,
                        BigInteger.valueOf(40)
                ),
                new SettlementRequest(
                        DEPLOYMENT, BigInteger.valueOf(999), BigInteger.TWO, ONE,
                        BigInteger.valueOf(40)
                ),
                new SettlementRequest(
                        DEPLOYMENT, ONE, BigInteger.valueOf(999), ONE,
                        BigInteger.valueOf(40)
                ),
                new SettlementRequest(
                        DEPLOYMENT, ONE, BigInteger.TWO, BigInteger.valueOf(999),
                        BigInteger.valueOf(40)
                ),
                new SettlementRequest(
                        DEPLOYMENT, ONE, ONE, BigInteger.TWO,
                        BigInteger.valueOf(40)
                ),
                new SettlementRequest(
                        DEPLOYMENT, ONE, BigInteger.TWO, ONE,
                        BigInteger.valueOf(41)
                )
        );

        for (SettlementRequest invalid : invalidRequests) {
            MockBlockchainGateway gateway = gateway();
            PublishedState before = publishedState(gateway);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> gateway.prepareSettlement(invalid)
            );

            assertEquals(before, publishedState(gateway));

            // Invalid preparation must not consume a transaction sequence.
            assertEquals(
                    gateway().prepareSettlement(standardRequest()),
                    gateway.prepareSettlement(standardRequest())
            );
        }
    }

    @Test
    void incompatiblePricesFailWithoutMutation() {
        MockBlockchainGateway gateway = gateway();
        OrderSnapshot originalBob = gateway.getOrder(BigInteger.TWO).orElseThrow();

        gateway.replaceOrderSnapshot(withPrice(originalBob, BigInteger.valueOf(9)));
        PublishedState before = publishedState(gateway);

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.prepareSettlement(standardRequest())
        );

        assertEquals(before, publishedState(gateway));

        gateway.replaceOrderSnapshot(originalBob);

        assertEquals(
                gateway().prepareSettlement(standardRequest()),
                gateway.prepareSettlement(standardRequest())
        );
    }

    @Test
    void successfulMiningRevalidatesThePendingRequest() {
        MockBlockchainGateway gateway = gateway();
        PreparedSettlement prepared =
                gateway.prepareSettlement(standardRequest());

        gateway.broadcastSettlement(prepared);

        OrderSnapshot bob = gateway.getOrder(BigInteger.TWO).orElseThrow();
        gateway.replaceOrderSnapshot(withPrice(bob, BigInteger.valueOf(9)));

        PublishedState before = publishedState(gateway);

        assertThrows(
                BlockchainGatewayException.class,
                () -> gateway.mineSuccessfulSettlement(prepared.transactionHash())
        );

        assertEquals(before, publishedState(gateway));
        assertEquals(
                TransactionStatus.PENDING,
                gateway.getTransaction(prepared.transactionHash()).status()
        );
    }

    @Test
    void changingOneGatewayDoesNotChangeAnother() {
        MockBlockchainGateway first = gateway();
        MockBlockchainGateway second = gateway();
        PublishedState secondBefore = publishedState(second);

        PreparedSettlement prepared = first.prepareSettlement(standardRequest());
        first.broadcastSettlement(prepared);
        first.mineSuccessfulSettlement(prepared.transactionHash());

        assertEquals(secondBefore, publishedState(second));
        assertEquals(
                TransactionStatus.NOT_FOUND,
                second.getTransaction(prepared.transactionHash()).status()
        );
    }

    @Test
    void concurrentReadsObserveValidPublishedValues() throws Exception {
        MockBlockchainGateway gateway = gateway();
        PreparedSettlement prepared =
                gateway.prepareSettlement(standardRequest());

        gateway.broadcastSettlement(prepared);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Callable<Void>> tasks = new ArrayList<>();

            tasks.add(() -> {
                start.await();
                gateway.mineSuccessfulSettlement(prepared.transactionHash());
                return null;
            });

            for (int reader = 0; reader < 3; reader++) {
                tasks.add(() -> {
                    start.await();

                    for (int iteration = 0; iteration < 20; iteration++) {
                        OrderSnapshot alice = gateway.getOrder(ONE).orElseThrow();
                        OrderSnapshot bob =
                                gateway.getOrder(BigInteger.TWO).orElseThrow();

                        assertTrue(
                                (alice.status() == OrderStatus.OPEN
                                        && alice.remainingLots().equals(
                                                BigInteger.valueOf(100)))
                                || (alice.status() == OrderStatus.PARTIALLY_FILLED
                                        && alice.remainingLots().equals(
                                                BigInteger.valueOf(60)))
                        );

                        assertTrue(
                                (bob.status() == OrderStatus.OPEN
                                        && bob.remainingLots().equals(
                                                BigInteger.valueOf(40)))
                                || (bob.status() == OrderStatus.FILLED
                                        && bob.remainingLots().equals(ZERO))
                        );

                        List<DexEvent> events = gateway.getEvents(range(100, 104));
                        assertTrue(events.size() == 3 || events.size() == 4);

                        for (int i = 1; i < events.size(); i++) {
                            assertTrue(
                                    events.get(i - 1).compareTo(events.get(i)) < 0
                            );
                        }

                        if (events.size() == 4) {
                            TradeExecutedEvent trade = assertInstanceOf(
                                    TradeExecutedEvent.class, events.get(3)
                            );
                            assertEquals(ZERO, trade.buyOrderRemainingLots());
                            assertEquals(
                                    BigInteger.valueOf(60),
                                    trade.sellOrderRemainingLots()
                            );
                        }

                        TransactionObservation observation =
                                gateway.getTransaction(prepared.transactionHash());

                        if (observation.status() == TransactionStatus.PENDING) {
                            assertNull(observation.blockNumber());
                            assertNull(observation.blockHash());
                        } else {
                            assertEquals(
                                    TransactionStatus.SUCCEEDED,
                                    observation.status()
                            );
                            assertEquals(
                                    BigInteger.valueOf(104), observation.blockNumber()
                            );
                            assertNotNull(observation.blockHash());
                        }

                        BigInteger aliceBalance = gateway.getTokenBalance(
                                MockBlockchainFixtures.BASE_TOKEN_ADDRESS,
                                MockBlockchainFixtures.ALICE_ADDRESS
                        );

                        assertTrue(
                                aliceBalance.equals(BigInteger.valueOf(100000))
                                        || aliceBalance.equals(BigInteger.valueOf(60000))
                        );
                    }

                    return null;
                });
            }

            List<Future<Void>> futures = new ArrayList<>();

            for (Callable<Void> task : tasks) {
                futures.add(executor.submit(task));
            }

            start.countDown();

            for (Future<Void> future : futures) {
                future.get(); // Propagates assertions and exceptions from workers.
            }

            assertEquals(
                    TransactionStatus.SUCCEEDED,
                    gateway.getTransaction(prepared.transactionHash()).status()
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private static SettlementRequest standardRequest() {
        return new SettlementRequest(
                DEPLOYMENT,
                ONE,
                BigInteger.TWO,
                ONE,
                BigInteger.valueOf(40)
        );
    }

    private static OrderSnapshot withPrice(
            OrderSnapshot order,
            BigInteger price
    ) {
        return new OrderSnapshot(
                order.deploymentId(),
                order.orderId(),
                order.marketId(),
                order.owner(),
                order.side(),
                price,
                order.originalLots(),
                order.remainingLots(),
                order.status()
        );
    }

    private static List<BigInteger> ledger(MockBlockchainGateway gateway) {
        var base = MockBlockchainFixtures.BASE_TOKEN_ADDRESS;
        var quote = MockBlockchainFixtures.QUOTE_TOKEN_ADDRESS;
        var alice = MockBlockchainFixtures.ALICE_ADDRESS;
        var bob = MockBlockchainFixtures.BOB_ADDRESS;
        var dex = MockBlockchainFixtures.DEX_ADDRESS;

        return List.of(
                gateway.getTokenBalance(base, alice),
                gateway.getTokenBalance(base, bob),
                gateway.getTokenBalance(quote, alice),
                gateway.getTokenBalance(quote, bob),
                gateway.getTokenAllowance(base, alice, dex),
                gateway.getTokenAllowance(quote, bob, dex)
        );
    }

    private static PublishedState publishedState(MockBlockchainGateway gateway) {
        return new PublishedState(
                gateway.getLatestBlock(),
                gateway.getOrder(ONE).orElseThrow(),
                gateway.getOrder(BigInteger.TWO).orElseThrow(),
                gateway.getEvents(new BlockRange(ZERO, BigInteger.valueOf(10000))),
                ledger(gateway),
                gateway.getPendingExecutorNonce()
        );
    }

    private record PublishedState(
            BlockReference latestBlock,
            OrderSnapshot alice,
            OrderSnapshot bob,
            List<DexEvent> events,
            List<BigInteger> ledger,
            BigInteger nonce
    ) {
    }
}