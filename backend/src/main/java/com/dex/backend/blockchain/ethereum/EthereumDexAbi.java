package com.dex.backend.blockchain.ethereum;

import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.StaticStruct;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;

import java.math.BigInteger;
import java.util.List;

/**
 * Web3j definitions matching the checked-in MarketRegistry and OrderBook ABIs.
 */
final class EthereumDexAbi {

    static final Event MARKET_REGISTERED = new Event(
            "MarketRegistered",
            List.of(
                    indexed(Uint256.class),
                    indexed(Address.class),
                    indexed(Address.class),
                    TypeReference.create(Uint256.class)
            )
    );

    static final Event ORDER_PLACED = new Event(
            "OrderPlaced",
            List.of(
                    indexed(Uint256.class),
                    indexed(Address.class),
                    indexed(Uint256.class),
                    TypeReference.create(Uint8.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class)
            )
    );

    static final Event ORDER_CANCELLED = new Event(
            "OrderCancelled",
            List.of(indexed(Uint256.class))
    );

    static final Event TRADE_EXECUTED = new Event(
            "TradeExecuted",
            List.of(
                    indexed(Uint256.class),
                    indexed(Uint256.class),
                    indexed(Uint256.class),
                    TypeReference.create(Address.class),
                    TypeReference.create(Address.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class)
            )
    );

    private EthereumDexAbi() {
    }

    static Function getMarket(BigInteger marketId) {
        return new Function(
                "getMarket",
                List.of(new Uint256(marketId)),
                List.of(
                        TypeReference.create(Address.class),
                        TypeReference.create(Address.class),
                        TypeReference.create(Uint256.class),
                        TypeReference.create(Bool.class)
                )
        );
    }

    static Function getOrder(BigInteger orderId) {
        return new Function(
                "getOrder",
                List.of(new Uint256(orderId)),
                List.of(new TypeReference<OrderStruct>() {
                })
        );
    }

    static Function executeTrade(
            BigInteger buyOrderId,
            BigInteger sellOrderId,
            BigInteger fillLots,
            BigInteger executionPrice
    ) {
        return new Function(
                "executeTrade",
                List.of(
                        new Uint256(buyOrderId),
                        new Uint256(sellOrderId),
                        new Uint256(fillLots),
                        new Uint256(executionPrice)
                ),
                List.of()
        );
    }

    static Function tokenSymbol() {
        return new Function(
                "symbol",
                List.of(),
                List.of(TypeReference.create(Utf8String.class))
        );
    }

    static Function tokenDecimals() {
        return new Function(
                "decimals",
                List.of(),
                List.of(TypeReference.create(Uint8.class))
        );
    }

    private static <T extends org.web3j.abi.datatypes.Type> TypeReference<T> indexed(
            Class<T> type
    ) {
        return TypeReference.create(type, true);
    }

    static final class OrderStruct extends StaticStruct {

        final Uint256 id;
        final Address owner;
        final Uint256 marketId;
        final Uint8 side;
        final Uint256 originalLots;
        final Uint256 remainingLots;
        final Uint256 limitPrice;
        final Uint8 status;

        public OrderStruct(
                Uint256 id,
                Address owner,
                Uint256 marketId,
                Uint8 side,
                Uint256 originalLots,
                Uint256 remainingLots,
                Uint256 limitPrice,
                Uint8 status
        ) {
            super(
                    id,
                    owner,
                    marketId,
                    side,
                    originalLots,
                    remainingLots,
                    limitPrice,
                    status
            );
            this.id = id;
            this.owner = owner;
            this.marketId = marketId;
            this.side = side;
            this.originalLots = originalLots;
            this.remainingLots = remainingLots;
            this.limitPrice = limitPrice;
            this.status = status;
        }
    }
}
