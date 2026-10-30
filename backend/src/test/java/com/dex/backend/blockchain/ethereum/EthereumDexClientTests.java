package com.dex.backend.blockchain.ethereum;

import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.blockchain.event.DexEvent;
import com.dex.backend.blockchain.event.MarketRegisteredEvent;
import com.dex.backend.blockchain.event.OrderCancelledEvent;
import com.dex.backend.blockchain.event.OrderPlacedEvent;
import com.dex.backend.blockchain.event.TradeExecutedEvent;
import com.dex.backend.blockchain.model.BlockRange;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.blockchain.model.MarketSnapshot;
import com.dex.backend.blockchain.model.OrderSnapshot;
import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EthereumDexClientTests {

    private static final String DEPLOYMENT_ID = "ethereum-dex-test";
    private static final EthereumAddress ORDER_BOOK = address("d");
    private static final EthereumAddress MARKET_REGISTRY = address("c");
    private static final EthereumAddress BASE = address("1");
    private static final EthereumAddress QUOTE = address("2");
    private static final EthereumAddress BUYER = address("3");
    private static final EthereumAddress SELLER = address("4");
    private static final BigInteger MARKET_ID = BigInteger.ONE;
    private static final BigInteger BUY_ORDER_ID = BigInteger.TEN;
    private static final BigInteger SELL_ORDER_ID = BigInteger.valueOf(11);
    private static final BigInteger LOT_SIZE = BigInteger.TEN.pow(18);
    private static final BigInteger PRICE = BigInteger.valueOf(2_500_000);

    private final EthereumRpcClient rpc = mock(EthereumRpcClient.class);
    private final EthereumDexClient client = new EthereumDexClient(
            rpc,
            DEPLOYMENT_ID,
            ORDER_BOOK,
            MARKET_REGISTRY
    );

    @Test
    void readsMarketsAndPreservesExactUint256Values() {
        BigInteger hugeLotSize = BigInteger.ONE.shiftLeft(200);
        when(rpc.callContract(
                eq(MARKET_REGISTRY),
                any(Function.class),
                any(DefaultBlockParameter.class),
                eq("market lookup"),
                eq(true)
        )).thenReturn(Optional.of(List.of(
                new Address(BASE.value()),
                new Address(QUOTE.value()),
                new Uint256(hugeLotSize),
                new Bool(true)
        )));

        MarketSnapshot market = client.getMarket(MARKET_ID).orElseThrow();

        assertThat(market).isEqualTo(new MarketSnapshot(
                DEPLOYMENT_ID,
                MARKET_ID,
                BASE,
                QUOTE,
                hugeLotSize,
                true
        ));

        ArgumentCaptor<Function> function = ArgumentCaptor.forClass(Function.class);
        verify(rpc).callContract(
                eq(MARKET_REGISTRY),
                function.capture(),
                any(DefaultBlockParameter.class),
                eq("market lookup"),
                eq(true)
        );
        assertThat(function.getValue().getName()).isEqualTo("getMarket");
        assertThat(FunctionEncoder.encode(function.getValue()))
                .startsWith("0x")
                .contains(leftPad64(MARKET_ID.toString(16)));
    }

    @Test
    void missingMarketAndOrderAreEmpty() {
        when(rpc.callContract(
                any(),
                any(),
                any(),
                anyString(),
                anyBoolean()
        )).thenReturn(Optional.empty());

        assertThat(client.getMarket(MARKET_ID)).isEmpty();
        assertThat(client.getOrder(BUY_ORDER_ID)).isEmpty();
    }

    @Test
    void mapsSolidityOrderEnumsAndDerivesPartialFillStatus() {
        BigInteger hugeId = BigInteger.ONE.shiftLeft(180);
        EthereumDexAbi.OrderStruct tuple = new EthereumDexAbi.OrderStruct(
                new Uint256(hugeId),
                new Address(BUYER.value()),
                new Uint256(MARKET_ID),
                new Uint8(BigInteger.ZERO),
                new Uint256(BigInteger.valueOf(100)),
                new Uint256(BigInteger.valueOf(60)),
                new Uint256(PRICE),
                new Uint8(BigInteger.ZERO)
        );
        when(rpc.callContract(
                eq(ORDER_BOOK),
                any(Function.class),
                any(DefaultBlockParameter.class),
                eq("order lookup"),
                eq(true)
        )).thenReturn(Optional.of(List.of(tuple)));

        OrderSnapshot order = client.getOrder(hugeId).orElseThrow();

        assertThat(order.orderId()).isEqualTo(hugeId);
        assertThat(order.side()).isEqualTo(OrderSide.BUY);
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order.remainingLots()).isEqualTo(BigInteger.valueOf(60));
        assertThat(order.limitPriceQuoteAtomsPerLot()).isEqualTo(PRICE);
    }

    @Test
    void rejectsUnknownSolidityEnumValuesWithoutLeakingData() {
        EthereumDexAbi.OrderStruct tuple = new EthereumDexAbi.OrderStruct(
                new Uint256(BUY_ORDER_ID),
                new Address(BUYER.value()),
                new Uint256(MARKET_ID),
                new Uint8(BigInteger.valueOf(2)),
                new Uint256(BigInteger.TEN),
                new Uint256(BigInteger.TEN),
                new Uint256(PRICE),
                new Uint8(BigInteger.ZERO)
        );
        when(rpc.callContract(any(), any(), any(), anyString(), anyBoolean()))
                .thenReturn(Optional.of(List.of(tuple)));
        when(rpc.nonRetryable(anyString())).thenAnswer(invocation ->
                new BlockchainGatewayException(invocation.getArgument(0), false)
        );

        assertThatThrownBy(() -> client.getOrder(BUY_ORDER_ID))
                .isInstanceOfSatisfying(
                        BlockchainGatewayException.class,
                        exception -> assertThat(exception.isRetryable()).isFalse()
                )
                .hasMessage("OrderBook returned an unknown side value");
    }

    @Test
    void decodesBothContractEventStreamsAndSortsByChainPosition() {
        BlockRange range = new BlockRange(
                BigInteger.valueOf(100),
                BigInteger.valueOf(103)
        );
        Log market = marketLog(100, 0, 0);
        Log placed = placedLog(101, 0, 1);
        Log trade = tradeLog(102, 1, 0);
        Log cancelled = cancelledLog(103, 0, 0);
        when(rpc.getLogs(range, List.of(MARKET_REGISTRY, ORDER_BOOK)))
                .thenReturn(List.of(cancelled, trade, market, placed));
        stubBlocks(100, 101, 102, 103);
        stubTokenMetadata();

        List<DexEvent> events = client.getEvents(range);

        assertThat(events).hasSize(4);
        MarketRegisteredEvent registered = (MarketRegisteredEvent) events.get(0);
        OrderPlacedEvent orderPlaced = (OrderPlacedEvent) events.get(1);
        TradeExecutedEvent executed = (TradeExecutedEvent) events.get(2);
        OrderCancelledEvent orderCancelled = (OrderCancelledEvent) events.get(3);

        assertThat(registered.marketId()).isEqualTo(MARKET_ID);
        assertThat(registered.baseSymbol()).isEqualTo("TKA");
        assertThat(registered.quoteSymbol()).isEqualTo("TKB");
        assertThat(registered.baseDecimals()).isEqualTo(18);
        assertThat(registered.baseLotSizeAtoms()).isEqualTo(LOT_SIZE);
        assertThat(orderPlaced.side()).isEqualTo(OrderSide.BUY);
        assertThat(orderPlaced.owner()).isEqualTo(BUYER);
        assertThat(orderPlaced.originalLots()).isEqualTo(BigInteger.valueOf(40));
        assertThat(orderPlaced.limitPriceQuoteAtomsPerLot()).isEqualTo(PRICE);
        assertThat(executed.tradeId()).isNull();
        assertThat(executed.buyer()).isEqualTo(BUYER);
        assertThat(executed.seller()).isEqualTo(SELLER);
        assertThat(executed.fillLots()).isEqualTo(BigInteger.valueOf(40));
        assertThat(executed.baseAmountAtoms())
                .isEqualTo(BigInteger.valueOf(40).multiply(LOT_SIZE));
        assertThat(executed.quoteAmountAtoms())
                .isEqualTo(BigInteger.valueOf(40).multiply(PRICE));
        assertThat(orderCancelled.orderId()).isEqualTo(SELL_ORDER_ID);
    }

    @Test
    void ignoresUnrelatedEventsButRejectsKnownEventFromWrongContract() {
        BlockRange range = new BlockRange(BigInteger.valueOf(100), BigInteger.valueOf(100));
        Log unrelated = log(
                ORDER_BOOK,
                100,
                0,
                0,
                List.of(hash("f").value()),
                "0x"
        );
        when(rpc.getLogs(range, List.of(MARKET_REGISTRY, ORDER_BOOK)))
                .thenReturn(List.of(unrelated));

        assertThat(client.getEvents(range)).isEmpty();

        Log wrongContract = marketLog(100, 0, 0);
        wrongContract.setAddress(ORDER_BOOK.value());
        when(rpc.getLogs(range, List.of(MARKET_REGISTRY, ORDER_BOOK)))
                .thenReturn(List.of(wrongContract));
        when(rpc.nonRetryable(anyString())).thenAnswer(invocation ->
                new BlockchainGatewayException(invocation.getArgument(0), false)
        );

        assertThatThrownBy(() -> client.getEvents(range))
                .isInstanceOf(BlockchainGatewayException.class)
                .hasMessage("Ethereum DEX event originated from the wrong contract");
    }

    private void stubBlocks(long... numbers) {
        for (long number : numbers) {
            when(rpc.getBlock(BigInteger.valueOf(number))).thenReturn(Optional.of(
                    new BlockReference(
                            BigInteger.valueOf(number),
                            blockHash(number),
                            Instant.ofEpochSecond(1_735_689_600L + number)
                    )
            ));
        }
    }

    private void stubTokenMetadata() {
        when(rpc.callContract(
                any(EthereumAddress.class),
                any(Function.class),
                any(DefaultBlockParameter.class),
                eq("token symbol"),
                eq(false)
        )).thenAnswer(invocation -> {
            EthereumAddress token = invocation.getArgument(0);
            return Optional.of(List.of(new Utf8String(
                    token.equals(BASE) ? "TKA" : "TKB"
            )));
        });
        when(rpc.callContract(
                any(EthereumAddress.class),
                any(Function.class),
                any(DefaultBlockParameter.class),
                eq("token decimals"),
                eq(false)
        )).thenReturn(Optional.of(List.of(new Uint8(BigInteger.valueOf(18)))));
    }

    private Log marketLog(long block, long transaction, long index) {
        return log(
                MARKET_REGISTRY,
                block,
                transaction,
                index,
                List.of(
                        EventEncoder.encode(EthereumDexAbi.MARKET_REGISTERED),
                        topic(new Uint256(MARKET_ID)),
                        topic(new Address(BASE.value())),
                        topic(new Address(QUOTE.value()))
                ),
                data(new Uint256(LOT_SIZE))
        );
    }

    private Log placedLog(long block, long transaction, long index) {
        return log(
                ORDER_BOOK,
                block,
                transaction,
                index,
                List.of(
                        EventEncoder.encode(EthereumDexAbi.ORDER_PLACED),
                        topic(new Uint256(BUY_ORDER_ID)),
                        topic(new Address(BUYER.value())),
                        topic(new Uint256(MARKET_ID))
                ),
                data(
                        new Uint8(BigInteger.ZERO),
                        new Uint256(BigInteger.valueOf(40)),
                        new Uint256(PRICE)
                )
        );
    }

    private Log cancelledLog(long block, long transaction, long index) {
        return log(
                ORDER_BOOK,
                block,
                transaction,
                index,
                List.of(
                        EventEncoder.encode(EthereumDexAbi.ORDER_CANCELLED),
                        topic(new Uint256(SELL_ORDER_ID))
                ),
                "0x"
        );
    }

    private Log tradeLog(long block, long transaction, long index) {
        BigInteger fill = BigInteger.valueOf(40);
        return log(
                ORDER_BOOK,
                block,
                transaction,
                index,
                List.of(
                        EventEncoder.encode(EthereumDexAbi.TRADE_EXECUTED),
                        topic(new Uint256(BUY_ORDER_ID)),
                        topic(new Uint256(SELL_ORDER_ID)),
                        topic(new Uint256(MARKET_ID))
                ),
                data(
                        new Address(BUYER.value()),
                        new Address(SELLER.value()),
                        new Uint256(fill),
                        new Uint256(PRICE),
                        new Uint256(fill.multiply(LOT_SIZE)),
                        new Uint256(fill.multiply(PRICE))
                )
        );
    }

    private Log log(
            EthereumAddress address,
            long block,
            long transaction,
            long index,
            List<String> topics,
            String data
    ) {
        Log log = new Log();
        log.setRemoved(false);
        log.setAddress(address.value());
        log.setBlockNumber("0x" + Long.toHexString(block));
        log.setBlockHash(blockHash(block).value());
        log.setTransactionHash(hash(Long.toHexString(block + transaction + index + 1)).value());
        log.setTransactionIndex("0x" + Long.toHexString(transaction));
        log.setLogIndex("0x" + Long.toHexString(index));
        log.setTopics(topics);
        log.setData(data);
        return log;
    }

    private static String topic(Type value) {
        return "0x" + TypeEncoder.encode(value);
    }

    private static String data(Type... values) {
        String encoded = FunctionEncoder.encodeConstructor(List.of(values));
        return encoded.startsWith("0x") ? encoded : "0x" + encoded;
    }

    private static EthereumAddress address(String digit) {
        return new EthereumAddress("0x" + digit.repeat(40));
    }

    private static Hash256 hash(String digit) {
        String normalized = digit.isEmpty() ? "0" : digit.substring(0, 1);
        return new Hash256("0x" + normalized.repeat(64));
    }

    private static Hash256 blockHash(long block) {
        return new Hash256("0x" + leftPad64(Long.toHexString(block)));
    }

    private static String leftPad64(String value) {
        return "0".repeat(64 - value.length()) + value;
    }
}
