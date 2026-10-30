package com.dex.backend.blockchain.mock;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.blockchain.event.DexEvent;
import com.dex.backend.blockchain.event.EventMetadata;
import com.dex.backend.blockchain.event.MarketRegisteredEvent;
import com.dex.backend.blockchain.event.OrderPlacedEvent;
import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.SettlementRequest;
import com.dex.backend.blockchain.execution.TransactionObservation;
import com.dex.backend.blockchain.model.BlockRange;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.blockchain.model.ChainIdentity;
import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.blockchain.model.MarketSnapshot;
import com.dex.backend.blockchain.model.OrderSnapshot;
import com.dex.backend.order.OrderStatus;
import com.dex.backend.blockchain.event.TradeExecutedEvent;
import com.dex.backend.blockchain.execution.TransactionStatus;
import com.dex.backend.order.OrderSide;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

public final class MockBlockchainGateway implements BlockchainGateway {

    private final ChainIdentity chainIdentity;

    private final NavigableMap<BigInteger, BlockReference> blocks =
            new TreeMap<>();

    private final List<DexEvent> events = new ArrayList<>();

    private final Map<BigInteger, MarketSnapshot> markets = new HashMap<>();
    private final Map<BigInteger, OrderSnapshot> orders = new HashMap<>();

    private final Map<TokenBalanceKey, BigInteger> balances = new HashMap<>();
    private final Map<TokenAllowanceKey, BigInteger> allowances = new HashMap<>();

    private final Map<Hash256, PreparedSettlement> preparedSettlements =
            new HashMap<>();

    private final Map<Hash256, TransactionObservation> transactions =
            new HashMap<>();

    private BigInteger pendingExecutorNonce = BigInteger.ZERO;

    // Start beyond the fixture transaction sequences 1001..1003.
    private BigInteger nextTransactionSequence = BigInteger.valueOf(1004);

    private BigInteger nextTradeId = BigInteger.ONE;

    public MockBlockchainGateway(String deploymentId) {
        requireArgument(deploymentId, "deploymentId");

        if (deploymentId.isBlank() || deploymentId.length() > 128) {
            throw new IllegalArgumentException(
                    "deploymentId must be nonblank and at most 128 characters"
            );
        }

        for (BlockReference fixture : MockBlockchainFixtures.INITIAL_BLOCKS) {
            BlockReference block = new BlockReference(
                    fixture.number(),
                    fixture.hash(),
                    fixture.timestamp()
            );

            blocks.put(block.number(), block);
        }

        chainIdentity = new ChainIdentity(
                deploymentId,
                MockBlockchainFixtures.CHAIN_ID,
                MockBlockchainFixtures.DEX_ADDRESS,
                MockBlockchainFixtures.DEX_ADDRESS,
                MockBlockchainFixtures.DEPLOYMENT_BLOCK,
                blocks.get(MockBlockchainFixtures.DEPLOYMENT_BLOCK).hash()
        );

        initializeMarket(deploymentId);
        initializeOrder(
                deploymentId,
                MockBlockchainFixtures.ALICE_ORDER_PLACED_EVENT
        );
        initializeOrder(
                deploymentId,
                MockBlockchainFixtures.BOB_ORDER_PLACED_EVENT
        );
        seedInitialLedger();
    }

    @Override
    public synchronized ChainIdentity getChainIdentity() {
        return chainIdentity;
    }

    @Override
    public synchronized BlockReference getLatestBlock() {
        return blocks.lastEntry().getValue();
    }

    @Override
    public synchronized Optional<BlockReference> getBlock(
            BigInteger blockNumber
    ) {
        requireNonNegative(blockNumber, "blockNumber");
        return Optional.ofNullable(blocks.get(blockNumber));
    }

    @Override
    public synchronized List<DexEvent> getEvents(BlockRange range) {
        requireArgument(range, "range");

        List<DexEvent> filteredAndSortedEvents = new ArrayList<>();

        for (DexEvent event : events) {
            BigInteger number = event.metadata().position().blockNumber();

            if (number.compareTo(range.fromInclusive()) >= 0
                    && number.compareTo(range.toInclusive()) <= 0) {
                filteredAndSortedEvents.add(event);
            }
        }

        filteredAndSortedEvents.sort(null);

        return List.copyOf(filteredAndSortedEvents);
    }

    @Override
    public synchronized Optional<MarketSnapshot> getMarket(
            BigInteger marketId
    ) {
        requireNonNegative(marketId, "marketId");
        return Optional.ofNullable(markets.get(marketId));
    }

    @Override
    public synchronized Optional<OrderSnapshot> getOrder(
            BigInteger orderId
    ) {
        requireNonNegative(orderId, "orderId");
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public synchronized BigInteger getTokenBalance(
            EthereumAddress token,
            EthereumAddress owner
    ) {
        requireArgument(token, "token");
        requireArgument(owner, "owner");

        return balances.getOrDefault(
                new TokenBalanceKey(token, owner),
                BigInteger.ZERO
        );
    }

    @Override
    public synchronized BigInteger getTokenAllowance(
            EthereumAddress token,
            EthereumAddress owner,
            EthereumAddress spender
    ) {
        requireArgument(token, "token");
        requireArgument(owner, "owner");
        requireArgument(spender, "spender");

        return allowances.getOrDefault(
                new TokenAllowanceKey(token, owner, spender),
                BigInteger.ZERO
        );
    }

    @Override
    public synchronized EthereumAddress getExecutorAddress() {
        return MockBlockchainFixtures.EXECUTOR_ADDRESS;
    }

    @Override
    public synchronized BigInteger getPendingExecutorNonce() {
        return pendingExecutorNonce;
    }

    @Override
    public synchronized PreparedSettlement prepareSettlement(
            SettlementRequest request
    ) {
        requireArgument(request, "request");
        validateSettlement(request);

        BigInteger sequence = nextTransactionSequence;
        Hash256 transactionHash = MockBlockchainFixtures.hash(sequence);

        // Synthetic bytes for this mock; not a real signed Ethereum transaction.
        String signedTransactionHex =
                "0xdeadbeef" + transactionHash.value().substring(2);

        PreparedSettlement prepared = new PreparedSettlement(
                request,
                MockBlockchainFixtures.EXECUTOR_ADDRESS,
                pendingExecutorNonce,
                transactionHash,
                signedTransactionHex
        );

        if (preparedSettlements.containsKey(transactionHash)
                || transactions.containsKey(transactionHash)) {
            throw new BlockchainGatewayException(
                    "Mock transaction sequence produced a duplicate hash",
                    false
            );
        }

        preparedSettlements.put(transactionHash, prepared);
        nextTransactionSequence = sequence.add(BigInteger.ONE);

        return prepared;
    }

    @Override
    public synchronized Hash256 broadcastSettlement(
            PreparedSettlement preparedSettlement
    ) {
        requireArgument(preparedSettlement, "preparedSettlement");

        PreparedSettlement stored =
                preparedSettlements.get(preparedSettlement.transactionHash());

        if (stored == null || !stored.equals(preparedSettlement)) {
            throw new IllegalArgumentException(
                    "Transaction must exactly match a transaction prepared by this gateway"
            );
        }

        Hash256 hash = stored.transactionHash();

        // Check this before the nonce: an already broadcast transaction has
        // consumed its nonce, but rebroadcast must remain idempotent.
        if (transactions.containsKey(hash)) {
            return hash;
        }

        if (!stored.nonce().equals(pendingExecutorNonce)) {
            throw new IllegalArgumentException(
                    "Prepared transaction nonce must equal the pending executor nonce"
            );
        }

        transactions.put(hash, TransactionObservation.pending(hash));
        pendingExecutorNonce = pendingExecutorNonce.add(BigInteger.ONE);

        return hash;
    }

    @Override
    public synchronized TransactionObservation getTransaction(
            Hash256 transactionHash
    ) {
        requireArgument(transactionHash, "transactionHash");

        return transactions.getOrDefault(
                transactionHash,
                TransactionObservation.notFound(transactionHash)
        );
    }

    public synchronized BlockReference mineSuccessfulSettlement(
            Hash256 transactionHash
    ) {
        PreparedSettlement prepared = requirePendingSettlement(transactionHash);
        SettlementRequest request = prepared.request();

        ValidatedSettlement settlement;

        try {
            settlement = validateSettlement(request);
        } catch (IllegalArgumentException exception) {
            throw new BlockchainGatewayException(
                    "Pending mock settlement no longer satisfies settlement rules",
                    false,
                    exception
            );
        }

        MarketSnapshot market = settlement.market();
        OrderSnapshot buy = settlement.buy();
        OrderSnapshot sell = settlement.sell();

        BigInteger executionPrice =
                request.executionPriceQuoteAtomsPerLot();

        BigInteger fill = request.fillLots();

        BigInteger baseAmount = fill.multiply(market.baseLotSizeAtoms());
        BigInteger quoteAmount = fill.multiply(executionPrice);

        // Stage ledger changes in independent maps.
        Map<TokenBalanceKey, BigInteger> updatedBalances =
                new HashMap<>(balances);

        Map<TokenAllowanceKey, BigInteger> updatedAllowances =
                new HashMap<>(allowances);

        EthereumAddress spender = chainIdentity.dexAddress();

        stageTransfer(
                updatedBalances,
                updatedAllowances,
                market.baseTokenAddress(),
                sell.owner(),
                buy.owner(),
                spender,
                baseAmount
        );

        stageTransfer(
                updatedBalances,
                updatedAllowances,
                market.quoteTokenAddress(),
                buy.owner(),
                sell.owner(),
                spender,
                quoteAmount
        );

        OrderSnapshot updatedBuy =
                orderAfterFill(buy, buy.remainingLots().subtract(fill));

        OrderSnapshot updatedSell =
                orderAfterFill(sell, sell.remainingLots().subtract(fill));

        BlockReference block = nextBlock();

        EventMetadata metadata = new EventMetadata(
                chainIdentity.deploymentId(),
                chainIdentity.dexAddress(),
                transactionHash,
                block.hash(),
                new ChainPosition(
                        block.number(),
                        BigInteger.ZERO,
                        BigInteger.ZERO
                ),
                block.timestamp()
        );

        TradeExecutedEvent event = new TradeExecutedEvent(
                metadata,
                nextTradeId,
                request.marketId(),
                buy.orderId(),
                sell.orderId(),
                fill,
                executionPrice,
                updatedBuy.remainingLots(),
                updatedSell.remainingLots()
        );

        TransactionObservation observation = TransactionObservation.succeeded(
                transactionHash,
                block.number(),
                block.hash()
        );

        // Commit only after all validation and construction have succeeded.
        balances.clear();
        balances.putAll(updatedBalances);

        allowances.clear();
        allowances.putAll(updatedAllowances);

        orders.put(updatedBuy.orderId(), updatedBuy);
        orders.put(updatedSell.orderId(), updatedSell);

        blocks.put(block.number(), block);
        events.add(event);
        transactions.put(transactionHash, observation);

        nextTradeId = nextTradeId.add(BigInteger.ONE);

        return block;
    }

    public synchronized BlockReference mineRevertedSettlement(
            Hash256 transactionHash,
            String failureReason
    ) {
        requirePendingSettlement(transactionHash);

        BlockReference block = nextBlock();

        TransactionObservation observation = TransactionObservation.reverted(
                transactionHash,
                block.number(),
                block.hash(),
                failureReason
        );

        blocks.put(block.number(), block);
        transactions.put(transactionHash, observation);

        return block;
    }

    public synchronized void replaceOrderSnapshot(OrderSnapshot replacement) {
        requireArgument(replacement, "replacement");
        requireDeployment(replacement.deploymentId());

        OrderSnapshot existing = orders.get(replacement.orderId());

        if (existing == null) {
            throw new IllegalArgumentException(
                    "Only an existing mock order may be replaced"
            );
        }

        if (!existing.marketId().equals(replacement.marketId())) {
            throw new IllegalArgumentException(
                    "Replacement order must retain its market"
            );
        }

        orders.put(replacement.orderId(), replacement);
    }

    private void initializeMarket(String deploymentId) {
        MarketRegisteredEvent fixture =
                MockBlockchainFixtures.MARKET_REGISTERED_EVENT;

        MarketRegisteredEvent event = new MarketRegisteredEvent(
                metadataFor(deploymentId, fixture.metadata()),
                fixture.marketId(),
                fixture.baseTokenAddress(),
                fixture.quoteTokenAddress(),
                fixture.baseSymbol(),
                fixture.quoteSymbol(),
                fixture.baseDecimals(),
                fixture.quoteDecimals(),
                fixture.baseLotSizeAtoms()
        );

        events.add(event);

        markets.put(
                event.marketId(),
                new MarketSnapshot(
                        deploymentId,
                        event.marketId(),
                        event.baseTokenAddress(),
                        event.quoteTokenAddress(),
                        event.baseLotSizeAtoms(),
                        true
                )
        );
    }

    private void initializeOrder(
            String deploymentId,
            OrderPlacedEvent fixture
    ) {
        OrderPlacedEvent event = new OrderPlacedEvent(
                metadataFor(deploymentId, fixture.metadata()),
                fixture.orderId(),
                fixture.marketId(),
                fixture.owner(),
                fixture.side(),
                fixture.limitPriceQuoteAtomsPerLot(),
                fixture.originalLots()
        );

        events.add(event);

        orders.put(
                event.orderId(),
                new OrderSnapshot(
                        deploymentId,
                        event.orderId(),
                        event.marketId(),
                        event.owner(),
                        event.side(),
                        event.limitPriceQuoteAtomsPerLot(),
                        event.originalLots(),
                        event.originalLots(),
                        OrderStatus.OPEN
                )
        );
    }

    private static EventMetadata metadataFor(
            String deploymentId,
            EventMetadata fixture
    ) {
        return new EventMetadata(
                deploymentId,
                fixture.contractAddress(),
                fixture.transactionHash(),
                fixture.blockHash(),
                fixture.position(),
                fixture.blockTimestamp()
        );
    }

    private void requireDeployment(String deploymentId) {
        if (!chainIdentity.deploymentId().equals(deploymentId)) {
            throw new IllegalArgumentException(
                    "Request deploymentId must match the configured deployment"
            );
        }
    }

    private static void requireArgument(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(
                    fieldName + " must not be null"
            );
        }
    }

    private static void requireNonNegative(
            BigInteger value,
            String fieldName
    ) {
        requireArgument(value, fieldName);

        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    fieldName + " must be non-negative"
            );
        }
    }

    private void seedInitialLedger() {
        MarketSnapshot market = markets.get(MockBlockchainFixtures.MARKET_ID);
        OrderSnapshot seller = orders.get(MockBlockchainFixtures.ALICE_ORDER_ID);
        OrderSnapshot buyer = orders.get(MockBlockchainFixtures.BOB_ORDER_ID);

        BigInteger sellerBase = seller.originalLots()
                .multiply(market.baseLotSizeAtoms());

        BigInteger buyerQuote = buyer.originalLots()
                .multiply(buyer.limitPriceQuoteAtomsPerLot());

        EthereumAddress spender = chainIdentity.dexAddress();

        balances.put(
                new TokenBalanceKey(market.baseTokenAddress(), seller.owner()),
                sellerBase
        );
        balances.put(
                new TokenBalanceKey(market.quoteTokenAddress(), buyer.owner()),
                buyerQuote
        );

        allowances.put(
                new TokenAllowanceKey(
                        market.baseTokenAddress(), seller.owner(), spender
                ),
                sellerBase
        );
        allowances.put(
                new TokenAllowanceKey(
                        market.quoteTokenAddress(), buyer.owner(), spender
                ),
                buyerQuote
        );
    }

    private ValidatedSettlement validateSettlement(SettlementRequest request) {
        requireDeployment(request.deploymentId());

        MarketSnapshot market = markets.get(request.marketId());
        OrderSnapshot buy = orders.get(request.buyOrderId());
        OrderSnapshot sell = orders.get(request.sellOrderId());

        if (market == null) {
            throw new IllegalArgumentException("Requested market does not exist");
        }

        if (!market.active()) {
            throw new IllegalArgumentException("Requested market must be active");
        }

        if (buy == null || sell == null) {
            throw new IllegalArgumentException("Both settlement orders must exist");
        }

        if (!request.marketId().equals(buy.marketId())
                || !request.marketId().equals(sell.marketId())) {
            throw new IllegalArgumentException(
                    "Both orders must belong to the requested market"
            );
        }

        if (buy.side() != OrderSide.BUY || sell.side() != OrderSide.SELL) {
            throw new IllegalArgumentException(
                    "Settlement must reference a BUY order and a SELL order"
            );
        }

        if (!buy.isMatchable() || !sell.isMatchable()) {
            throw new IllegalArgumentException(
                    "Both settlement orders must be matchable"
            );
        }

        if (buy.limitPriceQuoteAtomsPerLot()
                .compareTo(sell.limitPriceQuoteAtomsPerLot()) < 0) {
            throw new IllegalArgumentException(
                    "Buy price must be at least the sell price"
            );
        }

        if (request.executionPriceQuoteAtomsPerLot()
                .compareTo(sell.limitPriceQuoteAtomsPerLot()) < 0
                || request.executionPriceQuoteAtomsPerLot()
                .compareTo(buy.limitPriceQuoteAtomsPerLot()) > 0) {
            throw new IllegalArgumentException(
                    "Execution price must satisfy both order limits"
            );
        }

        if (request.fillLots().compareTo(buy.remainingLots()) > 0
                || request.fillLots().compareTo(sell.remainingLots()) > 0) {
            throw new IllegalArgumentException(
                    "Fill quantity must not exceed either order's remaining quantity"
            );
        }

        return new ValidatedSettlement(market, buy, sell);
    }

    private PreparedSettlement requirePendingSettlement(Hash256 transactionHash) {
        requireArgument(transactionHash, "transactionHash");

        TransactionObservation observation = transactions.get(transactionHash);

        if (observation == null
                || observation.status() != TransactionStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Only a currently PENDING transaction may be mined"
            );
        }

        PreparedSettlement prepared = preparedSettlements.get(transactionHash);

        if (prepared == null) {
            throw new BlockchainGatewayException(
                    "Pending mock transaction has no prepared settlement",
                    false
            );
        }

        // Model the executor's nonce ordering even when several transactions
        // have been broadcast before mining.
        for (PreparedSettlement other : preparedSettlements.values()) {
            TransactionObservation otherObservation =
                    transactions.get(other.transactionHash());

            if (otherObservation != null
                    && otherObservation.status() == TransactionStatus.PENDING
                    && other.executorAddress().equals(prepared.executorAddress())
                    && other.nonce().compareTo(prepared.nonce()) < 0) {
                throw new IllegalArgumentException(
                        "Mine earlier pending executor nonces first"
                );
            }
        }

        return prepared;
    }

    private BlockReference nextBlock() {
        BlockReference previous = blocks.lastEntry().getValue();
        BigInteger number = previous.number().add(BigInteger.ONE);

        return new BlockReference(
                number,
                MockBlockchainFixtures.hash(number),
                previous.timestamp().plusSeconds(
                        MockBlockchainFixtures.BLOCK_INTERVAL_SECONDS
                )
        );
    }

    private static OrderSnapshot orderAfterFill(
            OrderSnapshot order,
            BigInteger remainingLots
    ) {
        OrderStatus status = remainingLots.signum() == 0
                ? OrderStatus.FILLED
                : OrderStatus.PARTIALLY_FILLED;

        return new OrderSnapshot(
                order.deploymentId(),
                order.orderId(),
                order.marketId(),
                order.owner(),
                order.side(),
                order.limitPriceQuoteAtomsPerLot(),
                order.originalLots(),
                remainingLots,
                status
        );
    }

    private static void stageTransfer(
            Map<TokenBalanceKey, BigInteger> updatedBalances,
            Map<TokenAllowanceKey, BigInteger> updatedAllowances,
            EthereumAddress token,
            EthereumAddress sender,
            EthereumAddress recipient,
            EthereumAddress spender,
            BigInteger amount
    ) {
        TokenBalanceKey senderKey = new TokenBalanceKey(token, sender);
        TokenBalanceKey recipientKey = new TokenBalanceKey(token, recipient);

        TokenAllowanceKey allowanceKey =
                new TokenAllowanceKey(token, sender, spender);

        BigInteger senderBalance =
                updatedBalances.getOrDefault(senderKey, BigInteger.ZERO);

        BigInteger allowance =
                updatedAllowances.getOrDefault(allowanceKey, BigInteger.ZERO);

        if (senderBalance.compareTo(amount) < 0) {
            throw new BlockchainGatewayException(
                    "Insufficient token balance for mock settlement",
                    false
            );
        }

        if (allowance.compareTo(amount) < 0) {
            throw new BlockchainGatewayException(
                    "Insufficient token allowance for mock settlement",
                    false
            );
        }

        updatedBalances.put(senderKey, senderBalance.subtract(amount));

        // Read after debiting so a transfer to the same owner nets to zero.
        BigInteger recipientBalance =
                updatedBalances.getOrDefault(recipientKey, BigInteger.ZERO);

        updatedBalances.put(recipientKey, recipientBalance.add(amount));
        updatedAllowances.put(allowanceKey, allowance.subtract(amount));
    }

    private record ValidatedSettlement(
            MarketSnapshot market,
            OrderSnapshot buy,
            OrderSnapshot sell
    ) {
    }

    private record TokenBalanceKey(
            EthereumAddress token,
            EthereumAddress owner
    ) {
    }

    private record TokenAllowanceKey(
            EthereumAddress token,
            EthereumAddress owner,
            EthereumAddress spender
    ) {
    }
}
