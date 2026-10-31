package com.dex.backend.matcher;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.blockchain.model.ChainIdentity;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.blockchain.model.MarketSnapshot;
import com.dex.backend.blockchain.model.OrderSnapshot;
import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchPreflightServiceTests {

    private static final String DEPLOYMENT_ID = "preflight-test";
    private static final BigInteger MARKET_ID = BigInteger.ONE;
    private static final BigInteger BUY_ID = BigInteger.TEN;
    private static final BigInteger SELL_ID = BigInteger.valueOf(11);
    private static final BigInteger FILL_LOTS = BigInteger.valueOf(40);
    private static final BigInteger EXECUTION_PRICE = BigInteger.valueOf(100);
    private static final BigInteger LOT_SIZE = BigInteger.valueOf(1_000);

    private static final EthereumAddress DEX = address(1);
    private static final EthereumAddress BASE_TOKEN = address(2);
    private static final EthereumAddress QUOTE_TOKEN = address(3);
    private static final EthereumAddress BUYER = address(4);
    private static final EthereumAddress SELLER = address(5);
    private static final EthereumAddress EXECUTOR = address(6);

    private final BlockchainGateway gateway = mock(BlockchainGateway.class);
    private final MatchPreflightService service = new MatchPreflightService(gateway);

    @BeforeEach
    void validGatewayState() {
        when(gateway.getChainIdentity()).thenReturn(identity());
        when(gateway.getMarket(MARKET_ID)).thenReturn(Optional.of(market(true)));
        when(gateway.getOrder(BUY_ID)).thenReturn(Optional.of(buyOrder()));
        when(gateway.getOrder(SELL_ID)).thenReturn(Optional.of(sellOrder()));

        BigInteger sellerRequired = FILL_LOTS.multiply(LOT_SIZE);
        BigInteger buyerRequired = FILL_LOTS.multiply(EXECUTION_PRICE);

        when(gateway.getTokenBalance(BASE_TOKEN, SELLER)).thenReturn(sellerRequired);
        when(gateway.getTokenAllowance(BASE_TOKEN, SELLER, DEX)).thenReturn(sellerRequired);
        when(gateway.getTokenBalance(QUOTE_TOKEN, BUYER)).thenReturn(buyerRequired);
        when(gateway.getTokenAllowance(QUOTE_TOKEN, BUYER, DEX)).thenReturn(buyerRequired);
        when(gateway.getExecutorAddress()).thenReturn(EXECUTOR);
    }

    @Test
    void validProposalPassesPreflight() {
        MatchPreflightResult result = service.check(proposal());

        assertTrue(result.eligible());
        assertEquals(EXECUTOR, result.executorAddress());
        assertTrue(result.ineligibleOrderIds().isEmpty());
    }

    @Test
    void insufficientSellerFundingExcludesOnlySellOrder() {
        when(gateway.getTokenAllowance(BASE_TOKEN, SELLER, DEX))
                .thenReturn(BigInteger.valueOf(39_999));

        MatchPreflightResult result = service.check(proposal());

        assertFalse(result.eligible());
        assertEquals(Set.of(SELL_ID), result.ineligibleOrderIds());
        verify(gateway, never()).getExecutorAddress();
    }

    @Test
    void insufficientBuyerFundingExcludesOnlyBuyOrder() {
        when(gateway.getTokenBalance(QUOTE_TOKEN, BUYER))
                .thenReturn(BigInteger.valueOf(3_999));

        MatchPreflightResult result = service.check(proposal());

        assertEquals(Set.of(BUY_ID), result.ineligibleOrderIds());
    }

    @Test
    void insufficientFundingOnBothSidesExcludesBothOrders() {
        when(gateway.getTokenBalance(BASE_TOKEN, SELLER)).thenReturn(BigInteger.ZERO);
        when(gateway.getTokenAllowance(QUOTE_TOKEN, BUYER, DEX)).thenReturn(BigInteger.ZERO);

        MatchPreflightResult result = service.check(proposal());

        assertEquals(Set.of(BUY_ID, SELL_ID), result.ineligibleOrderIds());
    }

    @Test
    void missingOrInactiveMarketExcludesBothOrdersWithoutReadingOrders() {
        when(gateway.getMarket(MARKET_ID)).thenReturn(Optional.empty());

        MatchPreflightResult missing = service.check(proposal());

        assertEquals(Set.of(BUY_ID, SELL_ID), missing.ineligibleOrderIds());
        verify(gateway, never()).getOrder(BUY_ID);

        when(gateway.getMarket(MARKET_ID)).thenReturn(Optional.of(market(false)));
        MatchPreflightResult inactive = service.check(proposal());
        assertEquals(Set.of(BUY_ID, SELL_ID), inactive.ineligibleOrderIds());
    }

    @Test
    void staleOrderScopeStatusOrQuantityExcludesAffectedOrder() {
        OrderSnapshot staleBuy = new OrderSnapshot(
                DEPLOYMENT_ID,
                BUY_ID,
                BigInteger.TWO,
                BUYER,
                OrderSide.SELL,
                BigInteger.valueOf(110),
                BigInteger.valueOf(100),
                BigInteger.ZERO,
                OrderStatus.FILLED
        );
        when(gateway.getOrder(BUY_ID)).thenReturn(Optional.of(staleBuy));

        MatchPreflightResult result = service.check(proposal());

        assertEquals(Set.of(BUY_ID), result.ineligibleOrderIds());
    }

    @Test
    void unexpectedGatewayIdentityHaltsAsSafetyFailure() {
        when(gateway.getChainIdentity()).thenReturn(new ChainIdentity(
                "wrong-deployment",
                BigInteger.valueOf(31_337),
                DEX,
                DEX,
                BigInteger.ZERO,
                hash(1)
        ));

        assertThrows(MatcherSafetyException.class, () -> service.check(proposal()));
    }

    @Test
    void executionPriceOutsideCrossedRangeIsSafetyFailure() {
        MatchProposal invalidPrice = new MatchProposal(
                DEPLOYMENT_ID,
                MARKET_ID,
                BUY_ID,
                SELL_ID,
                FILL_LOTS,
                BigInteger.valueOf(111)
        );

        assertThrows(
                MatcherSafetyException.class,
                () -> service.check(invalidPrice)
        );
    }

    @Test
    void largeIntegerFundingCalculationsRemainExact() {
        BigInteger hugeFill = new BigInteger("90071992547409930000000001");
        BigInteger hugeLot = new BigInteger("90071992547409930000000003");
        BigInteger hugePrice = new BigInteger("90071992547409930000000005");
        MatchProposal hugeProposal = new MatchProposal(
                DEPLOYMENT_ID,
                MARKET_ID,
                BUY_ID,
                SELL_ID,
                hugeFill,
                hugePrice
        );
        when(gateway.getMarket(MARKET_ID)).thenReturn(Optional.of(
                new MarketSnapshot(
                        DEPLOYMENT_ID,
                        MARKET_ID,
                        BASE_TOKEN,
                        QUOTE_TOKEN,
                        hugeLot,
                        true
                )
        ));
        when(gateway.getOrder(BUY_ID)).thenReturn(Optional.of(order(
                BUY_ID,
                BUYER,
                OrderSide.BUY,
                hugePrice.add(BigInteger.ONE),
                hugeFill
        )));
        when(gateway.getOrder(SELL_ID)).thenReturn(Optional.of(order(
                SELL_ID,
                SELLER,
                OrderSide.SELL,
                hugePrice.subtract(BigInteger.ONE),
                hugeFill
        )));
        BigInteger sellerRequired = hugeFill.multiply(hugeLot);
        BigInteger buyerRequired = hugeFill.multiply(hugePrice);
        when(gateway.getTokenBalance(BASE_TOKEN, SELLER)).thenReturn(sellerRequired);
        when(gateway.getTokenAllowance(BASE_TOKEN, SELLER, DEX)).thenReturn(sellerRequired);
        when(gateway.getTokenBalance(QUOTE_TOKEN, BUYER)).thenReturn(buyerRequired);
        when(gateway.getTokenAllowance(QUOTE_TOKEN, BUYER, DEX)).thenReturn(buyerRequired);

        assertTrue(service.check(hugeProposal).eligible());
    }

    @Test
    void gatewayFailuresPropagateToWorkerPolicy() {
        BlockchainGatewayException failure = new BlockchainGatewayException(
                "RPC unavailable",
                true
        );
        when(gateway.getMarket(MARKET_ID)).thenThrow(failure);

        assertEquals(
                failure,
                assertThrows(
                        BlockchainGatewayException.class,
                        () -> service.check(proposal())
                )
        );
    }

    private static MatchProposal proposal() {
        return new MatchProposal(
                DEPLOYMENT_ID,
                MARKET_ID,
                BUY_ID,
                SELL_ID,
                FILL_LOTS,
                EXECUTION_PRICE
        );
    }

    private static ChainIdentity identity() {
        return new ChainIdentity(
                DEPLOYMENT_ID,
                BigInteger.valueOf(31_337),
                DEX,
                DEX,
                BigInteger.ZERO,
                hash(1)
        );
    }

    private static MarketSnapshot market(boolean active) {
        return new MarketSnapshot(
                DEPLOYMENT_ID,
                MARKET_ID,
                BASE_TOKEN,
                QUOTE_TOKEN,
                LOT_SIZE,
                active
        );
    }

    private static OrderSnapshot buyOrder() {
        return order(
                BUY_ID,
                BUYER,
                OrderSide.BUY,
                BigInteger.valueOf(110),
                BigInteger.valueOf(50)
        );
    }

    private static OrderSnapshot sellOrder() {
        return order(
                SELL_ID,
                SELLER,
                OrderSide.SELL,
                BigInteger.valueOf(90),
                BigInteger.valueOf(60)
        );
    }

    private static OrderSnapshot order(
            BigInteger orderId,
            EthereumAddress owner,
            OrderSide side,
            BigInteger price,
            BigInteger remaining
    ) {
        return new OrderSnapshot(
                DEPLOYMENT_ID,
                orderId,
                MARKET_ID,
                owner,
                side,
                price,
                remaining,
                remaining,
                OrderStatus.OPEN
        );
    }

    private static EthereumAddress address(long value) {
        return new EthereumAddress("0x" + String.format("%040x", value));
    }

    private static Hash256 hash(long value) {
        return new Hash256("0x" + String.format("%064x", value));
    }
}
