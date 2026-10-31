package com.dex.backend.blockchain.ethereum;

import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.blockchain.event.DexEvent;
import com.dex.backend.blockchain.event.EventMetadata;
import com.dex.backend.blockchain.event.MarketRegisteredEvent;
import com.dex.backend.blockchain.event.OrderCancelledEvent;
import com.dex.backend.blockchain.event.OrderPlacedEvent;
import com.dex.backend.blockchain.event.TradeExecutedEvent;
import com.dex.backend.blockchain.model.BlockRange;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.blockchain.model.MarketSnapshot;
import com.dex.backend.blockchain.model.OrderSnapshot;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;
import org.springframework.util.StringUtils;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ABI-specific reads and event decoding for the current MarketRegistry and
 * OrderBook contracts.
 *
 * <p>This is intentionally not a {@code BlockchainGateway}: settlement
 * encoding and signing are added in a later checkpoint, after which the full
 * Ethereum adapter can be registered atomically.
 */
public final class EthereumDexClient {

    private static final String MARKET_REGISTERED_TOPIC =
            EventEncoder.encode(EthereumDexAbi.MARKET_REGISTERED);
    private static final String ORDER_PLACED_TOPIC =
            EventEncoder.encode(EthereumDexAbi.ORDER_PLACED);
    private static final String ORDER_CANCELLED_TOPIC =
            EventEncoder.encode(EthereumDexAbi.ORDER_CANCELLED);
    private static final String TRADE_EXECUTED_TOPIC =
            EventEncoder.encode(EthereumDexAbi.TRADE_EXECUTED);

    private final EthereumRpcClient rpcClient;
    private final String deploymentId;
    private final EthereumAddress orderBookAddress;
    private final EthereumAddress marketRegistryAddress;

    public EthereumDexClient(
            EthereumRpcClient rpcClient,
            String deploymentId,
            EthereumAddress orderBookAddress,
            EthereumAddress marketRegistryAddress
    ) {
        this.rpcClient = Objects.requireNonNull(
                rpcClient,
                "rpcClient must not be null"
        );
        this.deploymentId = requireDeploymentId(deploymentId);
        this.orderBookAddress = Objects.requireNonNull(
                orderBookAddress,
                "orderBookAddress must not be null"
        );
        this.marketRegistryAddress = Objects.requireNonNull(
                marketRegistryAddress,
                "marketRegistryAddress must not be null"
        );
        if (orderBookAddress.equals(marketRegistryAddress)) {
            throw new IllegalArgumentException(
                    "OrderBook and MarketRegistry addresses must differ"
            );
        }
    }

    public static EthereumDexClient fromProperties(
            Web3j web3j,
            DexProperties properties
    ) {
        Objects.requireNonNull(properties, "properties must not be null");
        if (properties.blockchain().mode() != BlockchainMode.ETHEREUM) {
            throw new IllegalArgumentException(
                    "Ethereum DEX client requires ETHEREUM blockchain mode"
            );
        }

        return new EthereumDexClient(
                EthereumRpcClient.fromProperties(web3j, properties),
                properties.blockchain().deploymentId(),
                new EthereumAddress(properties.blockchain().dexAddress()),
                new EthereumAddress(
                        properties.blockchain().marketRegistryAddress()
                )
        );
    }

    public List<DexEvent> getEvents(BlockRange range) {
        Objects.requireNonNull(range, "range must not be null");
        List<Log> logs = rpcClient.getLogs(
                range,
                List.of(marketRegistryAddress, orderBookAddress)
        );
        Map<BigInteger, BlockReference> blocks = new HashMap<>();
        List<DexEvent> events = new ArrayList<>();

        for (Log log : logs) {
            Optional<DexEvent> decoded = decodeRelevantLog(log, range, blocks);
            decoded.ifPresent(events::add);
        }

        events.sort(Comparator.naturalOrder());
        for (int index = 1; index < events.size(); index++) {
            if (events.get(index - 1).metadata().position().equals(
                    events.get(index).metadata().position()
            )) {
                throw rpcClient.nonRetryable(
                        "Ethereum logs contain a duplicate chain position"
                );
            }
        }
        return List.copyOf(events);
    }

    public Optional<MarketSnapshot> getMarket(BigInteger marketId) {
        requireNonNegative(marketId, "marketId");
        Optional<List<Type>> result = rpcClient.callContract(
                marketRegistryAddress,
                EthereumDexAbi.getMarket(marketId),
                DefaultBlockParameterName.LATEST,
                "market lookup",
                true
        );
        if (result.isEmpty()) {
            return Optional.empty();
        }

        try {
            List<Type> values = result.orElseThrow();
            requireTypes(values, Address.class, Address.class, Uint256.class, Bool.class);
            boolean registered = ((Bool) values.get(3)).getValue();
            if (!registered) {
                throw rpcClient.nonRetryable(
                        "MarketRegistry returned an unregistered market"
                );
            }
            return Optional.of(new MarketSnapshot(
                    deploymentId,
                    marketId,
                    address(values.get(0)),
                    address(values.get(1)),
                    uint(values.get(2)),
                    true
            ));
        } catch (BlockchainGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw rpcClient.nonRetryable(
                    "MarketRegistry returned malformed market data"
            );
        }
    }

    public Optional<OrderSnapshot> getOrder(BigInteger orderId) {
        requireNonNegative(orderId, "orderId");
        Optional<List<Type>> result = rpcClient.callContract(
                orderBookAddress,
                EthereumDexAbi.getOrder(orderId),
                DefaultBlockParameterName.LATEST,
                "order lookup",
                true
        );
        if (result.isEmpty()) {
            return Optional.empty();
        }

        try {
            List<Type> values = result.orElseThrow();
            if (values.size() != 1
                    || !(values.getFirst() instanceof EthereumDexAbi.OrderStruct order)) {
                throw new IllegalArgumentException("Unexpected order tuple");
            }
            BigInteger originalLots = order.originalLots.getValue();
            BigInteger remainingLots = order.remainingLots.getValue();
            return Optional.of(new OrderSnapshot(
                    deploymentId,
                    order.id.getValue(),
                    order.marketId.getValue(),
                    new EthereumAddress(order.owner.getValue()),
                    mapSide(order.side.getValue()),
                    order.limitPrice.getValue(),
                    originalLots,
                    remainingLots,
                    mapStatus(
                            order.status.getValue(),
                            originalLots,
                            remainingLots
                    )
            ));
        } catch (BlockchainGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw rpcClient.nonRetryable(
                    "OrderBook returned malformed order data"
            );
        }
    }

    private Optional<DexEvent> decodeRelevantLog(
            Log log,
            BlockRange range,
            Map<BigInteger, BlockReference> blocks
    ) {
        try {
            Objects.requireNonNull(log, "log must not be null");
            List<String> topics = log.getTopics();
            if (topics == null || topics.isEmpty()) {
                return Optional.empty();
            }

            String signature = normalizeTopic(topics.getFirst());
            Event event = eventForSignature(signature);
            if (event == null) {
                return Optional.empty();
            }
            if (log.isRemoved()) {
                throw rpcClient.nonRetryable(
                        "Ethereum returned a removed DEX log"
                );
            }

            EthereumAddress contractAddress = new EthereumAddress(log.getAddress());
            verifyEventContract(event, contractAddress);
            BigInteger blockNumber = requireNonNegative(
                    log.getBlockNumber(),
                    "log block number"
            );
            if (blockNumber.compareTo(range.fromInclusive()) < 0
                    || blockNumber.compareTo(range.toInclusive()) > 0) {
                throw rpcClient.nonRetryable(
                        "Ethereum returned a log outside the requested range"
                );
            }

            Hash256 blockHash = new Hash256(log.getBlockHash());
            BlockReference block = blocks.computeIfAbsent(
                    blockNumber,
                    this::requireCanonicalBlock
            );
            if (!block.hash().equals(blockHash)) {
                throw rpcClient.nonRetryable(
                        "Ethereum log block identity does not match its block"
                );
            }

            EventMetadata metadata = new EventMetadata(
                    deploymentId,
                    contractAddress,
                    new Hash256(log.getTransactionHash()),
                    blockHash,
                    new ChainPosition(
                            blockNumber,
                            requireNonNegative(
                                    log.getTransactionIndex(),
                                    "transaction index"
                            ),
                            requireNonNegative(log.getLogIndex(), "log index")
                    ),
                    block.timestamp()
            );

            return Optional.of(decodeEvent(event, metadata, topics, log.getData()));
        } catch (BlockchainGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw rpcClient.nonRetryable("Ethereum DEX log is malformed");
        }
    }

    private DexEvent decodeEvent(
            Event event,
            EventMetadata metadata,
            List<String> topics,
            String data
    ) {
        int expectedTopics = event.getIndexedParameters().size() + 1;
        if (topics.size() != expectedTopics) {
            throw new IllegalArgumentException("Unexpected event topic count");
        }
        List<Type> nonIndexed = decodeNonIndexed(event, data);

        if (event == EthereumDexAbi.MARKET_REGISTERED) {
            requireTypes(nonIndexed, Uint256.class);
            EthereumAddress base = indexedAddress(topics.get(2));
            EthereumAddress quote = indexedAddress(topics.get(3));
            DefaultBlockParameter eventBlock = DefaultBlockParameter.valueOf(
                    metadata.position().blockNumber()
            );
            return new MarketRegisteredEvent(
                    metadata,
                    indexedUint(topics.get(1)),
                    base,
                    quote,
                    tokenSymbol(base, eventBlock),
                    tokenSymbol(quote, eventBlock),
                    tokenDecimals(base, eventBlock),
                    tokenDecimals(quote, eventBlock),
                    uint(nonIndexed.getFirst())
            );
        }

        if (event == EthereumDexAbi.ORDER_PLACED) {
            requireTypes(nonIndexed, Uint8.class, Uint256.class, Uint256.class);
            return new OrderPlacedEvent(
                    metadata,
                    indexedUint(topics.get(1)),
                    indexedUint(topics.get(3)),
                    indexedAddress(topics.get(2)),
                    mapSide(uint(nonIndexed.get(0))),
                    uint(nonIndexed.get(2)),
                    uint(nonIndexed.get(1))
            );
        }

        if (event == EthereumDexAbi.ORDER_CANCELLED) {
            requireTypes(nonIndexed);
            return new OrderCancelledEvent(
                    metadata,
                    indexedUint(topics.get(1))
            );
        }

        requireTypes(
                nonIndexed,
                Address.class,
                Address.class,
                Uint256.class,
                Uint256.class,
                Uint256.class,
                Uint256.class
        );
        return new TradeExecutedEvent(
                metadata,
                null,
                indexedUint(topics.get(3)),
                indexedUint(topics.get(1)),
                indexedUint(topics.get(2)),
                uint(nonIndexed.get(2)),
                uint(nonIndexed.get(3)),
                address(nonIndexed.get(0)),
                address(nonIndexed.get(1)),
                uint(nonIndexed.get(4)),
                uint(nonIndexed.get(5))
        );
    }

    private String tokenSymbol(
            EthereumAddress token,
            DefaultBlockParameter block
    ) {
        List<Type> result = rpcClient.callContract(
                token,
                EthereumDexAbi.tokenSymbol(),
                block,
                "token symbol",
                false
        ).orElseThrow();
        requireTypes(result, Utf8String.class);
        String symbol = ((Utf8String) result.getFirst()).getValue();
        if (!StringUtils.hasText(symbol)) {
            throw new IllegalArgumentException("Token symbol is blank");
        }
        return symbol;
    }

    private int tokenDecimals(
            EthereumAddress token,
            DefaultBlockParameter block
    ) {
        List<Type> result = rpcClient.callContract(
                token,
                EthereumDexAbi.tokenDecimals(),
                block,
                "token decimals",
                false
        ).orElseThrow();
        requireTypes(result, Uint8.class);
        return uint(result.getFirst()).intValueExact();
    }

    private BlockReference requireCanonicalBlock(BigInteger blockNumber) {
        return rpcClient.getBlock(blockNumber).orElseThrow(() ->
                rpcClient.nonRetryable("Ethereum log block does not exist")
        );
    }

    private void verifyEventContract(
            Event event,
            EthereumAddress contractAddress
    ) {
        EthereumAddress expected = event == EthereumDexAbi.MARKET_REGISTERED
                ? marketRegistryAddress
                : orderBookAddress;
        if (!expected.equals(contractAddress)) {
            throw rpcClient.nonRetryable(
                    "Ethereum DEX event originated from the wrong contract"
            );
        }
    }

    private Event eventForSignature(String signature) {
        if (MARKET_REGISTERED_TOPIC.equals(signature)) {
            return EthereumDexAbi.MARKET_REGISTERED;
        }
        if (ORDER_PLACED_TOPIC.equals(signature)) {
            return EthereumDexAbi.ORDER_PLACED;
        }
        if (ORDER_CANCELLED_TOPIC.equals(signature)) {
            return EthereumDexAbi.ORDER_CANCELLED;
        }
        if (TRADE_EXECUTED_TOPIC.equals(signature)) {
            return EthereumDexAbi.TRADE_EXECUTED;
        }
        return null;
    }

    private List<Type> decodeNonIndexed(Event event, String data) {
        if (data == null) {
            throw new IllegalArgumentException("Event data is missing");
        }
        return FunctionReturnDecoder.decode(
                data,
                event.getNonIndexedParameters()
        );
    }

    private BigInteger indexedUint(String topic) {
        Type value = FunctionReturnDecoder.decodeIndexedValue(
                normalizeTopic(topic),
                TypeReference.create(Uint256.class)
        );
        return uint(value);
    }

    private EthereumAddress indexedAddress(String topic) {
        Type value = FunctionReturnDecoder.decodeIndexedValue(
                normalizeTopic(topic),
                TypeReference.create(Address.class)
        );
        return address(value);
    }

    private String normalizeTopic(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Event topic is missing");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private EthereumAddress address(Type value) {
        if (!(value instanceof Address address)) {
            throw new IllegalArgumentException("Expected an address");
        }
        return new EthereumAddress(address.getValue());
    }

    private BigInteger uint(Type value) {
        if (!(value instanceof org.web3j.abi.datatypes.NumericType numeric)) {
            throw new IllegalArgumentException("Expected an unsigned integer");
        }
        BigInteger number = numeric.getValue();
        if (number.signum() < 0) {
            throw new IllegalArgumentException("Unsigned integer is negative");
        }
        return number;
    }

    @SafeVarargs
    private final void requireTypes(
            List<Type> values,
            Class<? extends Type>... types
    ) {
        if (values.size() != types.length) {
            throw new IllegalArgumentException("Unexpected decoded value count");
        }
        for (int index = 0; index < types.length; index++) {
            if (!types[index].isInstance(values.get(index))) {
                throw new IllegalArgumentException("Unexpected decoded value type");
            }
        }
    }

    private OrderSide mapSide(BigInteger side) {
        if (BigInteger.ZERO.equals(side)) {
            return OrderSide.BUY;
        }
        if (BigInteger.ONE.equals(side)) {
            return OrderSide.SELL;
        }
        throw rpcClient.nonRetryable("OrderBook returned an unknown side value");
    }

    private OrderStatus mapStatus(
            BigInteger status,
            BigInteger originalLots,
            BigInteger remainingLots
    ) {
        if (BigInteger.ZERO.equals(status)) {
            return remainingLots.equals(originalLots)
                    ? OrderStatus.OPEN
                    : OrderStatus.PARTIALLY_FILLED;
        }
        if (BigInteger.ONE.equals(status)) {
            return OrderStatus.FILLED;
        }
        if (BigInteger.TWO.equals(status)) {
            return OrderStatus.CANCELLED;
        }
        throw rpcClient.nonRetryable("OrderBook returned an unknown status value");
    }

    private static BigInteger requireNonNegative(
            BigInteger value,
            String fieldName
    ) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    fieldName + " must be non-negative"
            );
        }
        return value;
    }

    private static String requireDeploymentId(String value) {
        Objects.requireNonNull(value, "deploymentId must not be null");
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(
                    "deploymentId must be nonblank and at most 128 characters"
            );
        }
        return value;
    }
}
