package com.dex.backend.matcher;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.blockchain.model.ChainIdentity;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.MarketSnapshot;
import com.dex.backend.blockchain.model.OrderSnapshot;
import com.dex.backend.order.OrderSide;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Service
public class MatchPreflightService {

    private final BlockchainGateway gateway;

    public MatchPreflightService(BlockchainGateway gateway) {
        this.gateway = gateway;
    }

    public MatchPreflightResult check(MatchProposal proposal) {
        ChainIdentity identity = gateway.getChainIdentity();
        requireDeployment(proposal, identity);

        Optional<MarketSnapshot> marketResult = gateway.getMarket(
                proposal.marketId()
        );

        if (marketResult.isEmpty()) {
            return bothOrdersIneligible(proposal);
        }

        MarketSnapshot market = marketResult.orElseThrow();
        requireMarketIdentity(proposal, market);

        if (!market.active()) {
            return bothOrdersIneligible(proposal);
        }

        Optional<OrderSnapshot> buyResult = gateway.getOrder(
                proposal.buyOrderId()
        );
        Optional<OrderSnapshot> sellResult = gateway.getOrder(
                proposal.sellOrderId()
        );
        Set<BigInteger> ineligibleOrderIds = new HashSet<>();

        buyResult.ifPresentOrElse(
                buy -> validateOrder(
                        proposal,
                        buy,
                        OrderSide.BUY,
                        ineligibleOrderIds
                ),
                () -> ineligibleOrderIds.add(proposal.buyOrderId())
        );
        sellResult.ifPresentOrElse(
                sell -> validateOrder(
                        proposal,
                        sell,
                        OrderSide.SELL,
                        ineligibleOrderIds
                ),
                () -> ineligibleOrderIds.add(proposal.sellOrderId())
        );

        if (!ineligibleOrderIds.isEmpty()) {
            return MatchPreflightResult.ineligible(ineligibleOrderIds);
        }

        OrderSnapshot buy = buyResult.orElseThrow();
        OrderSnapshot sell = sellResult.orElseThrow();

        if (buy.limitPriceQuoteAtomsPerLot().compareTo(
                sell.limitPriceQuoteAtomsPerLot()
        ) < 0) {
            return bothOrdersIneligible(proposal);
        }

        if (proposal.executionPriceQuoteAtomsPerLot().compareTo(
                sell.limitPriceQuoteAtomsPerLot()
        ) < 0 || proposal.executionPriceQuoteAtomsPerLot().compareTo(
                buy.limitPriceQuoteAtomsPerLot()
        ) > 0) {
            throw new MatcherSafetyException(
                    "Proposed execution price is outside the crossed price range"
            );
        }

        BigInteger sellerBaseRequired = proposal.fillLots()
                .multiply(market.baseLotSizeAtoms());
        BigInteger buyerQuoteRequired = proposal.fillLots()
                .multiply(proposal.executionPriceQuoteAtomsPerLot());

        checkFunding(
                market.baseTokenAddress(),
                sell,
                identity.dexAddress(),
                sellerBaseRequired,
                ineligibleOrderIds
        );
        checkFunding(
                market.quoteTokenAddress(),
                buy,
                identity.dexAddress(),
                buyerQuoteRequired,
                ineligibleOrderIds
        );

        if (!ineligibleOrderIds.isEmpty()) {
            return MatchPreflightResult.ineligible(ineligibleOrderIds);
        }

        return MatchPreflightResult.eligible(gateway.getExecutorAddress());
    }

    private void validateOrder(
            MatchProposal proposal,
            OrderSnapshot order,
            OrderSide expectedSide,
            Set<BigInteger> ineligibleOrderIds
    ) {
        BigInteger expectedOrderId = expectedSide == OrderSide.BUY
                ? proposal.buyOrderId()
                : proposal.sellOrderId();

        if (!proposal.deploymentId().equals(order.deploymentId())
                || !expectedOrderId.equals(order.orderId())) {
            throw new MatcherSafetyException(
                    "Blockchain gateway returned an order with unexpected identity"
            );
        }

        if (!proposal.marketId().equals(order.marketId())
                || order.side() != expectedSide
                || !order.isMatchable()
                || order.remainingLots().compareTo(proposal.fillLots()) < 0) {
            ineligibleOrderIds.add(expectedOrderId);
        }
    }

    private void checkFunding(
            EthereumAddress token,
            OrderSnapshot order,
            EthereumAddress spender,
            BigInteger requiredAmount,
            Set<BigInteger> ineligibleOrderIds
    ) {
        BigInteger balance = gateway.getTokenBalance(token, order.owner());
        BigInteger allowance = gateway.getTokenAllowance(
                token,
                order.owner(),
                spender
        );

        if (balance.compareTo(requiredAmount) < 0
                || allowance.compareTo(requiredAmount) < 0) {
            ineligibleOrderIds.add(order.orderId());
        }
    }

    private void requireDeployment(
            MatchProposal proposal,
            ChainIdentity identity
    ) {
        if (!proposal.deploymentId().equals(identity.deploymentId())) {
            throw new MatcherSafetyException(
                    "Match proposal and blockchain gateway deployments differ"
            );
        }
    }

    private void requireMarketIdentity(
            MatchProposal proposal,
            MarketSnapshot market
    ) {
        if (!proposal.deploymentId().equals(market.deploymentId())
                || !proposal.marketId().equals(market.marketId())) {
            throw new MatcherSafetyException(
                    "Blockchain gateway returned a market with unexpected identity"
            );
        }
    }

    private MatchPreflightResult bothOrdersIneligible(MatchProposal proposal) {
        return MatchPreflightResult.ineligible(Set.of(
                proposal.buyOrderId(),
                proposal.sellOrderId()
        ));
    }
}
