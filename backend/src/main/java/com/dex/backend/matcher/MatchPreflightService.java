package com.dex.backend.matcher;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.blockchain.model.ChainIdentity;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.MarketSnapshot;
import com.dex.backend.blockchain.model.OrderSnapshot;
import com.dex.backend.order.OrderSide;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class MatchPreflightService {

    private final BlockchainGateway gateway;

    public MatchPreflightService(BlockchainGateway gateway) {
        this.gateway = gateway;
    }

    public MatchPreflightResult check(MatchBatch proposal) {
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

        Map<BigInteger, OrderSnapshot> orders = new HashMap<>();
        Set<BigInteger> ineligibleOrderIds = new HashSet<>();
        Set<BigInteger> orderIds = allOrderIds(proposal);

        for (BigInteger orderId : orderIds) {
            Optional<OrderSnapshot> result = gateway.getOrder(orderId);
            if (result.isEmpty()) {
                ineligibleOrderIds.add(orderId);
            } else {
                OrderSnapshot order = result.orElseThrow();
                if (!proposal.deploymentId().equals(order.deploymentId())
                        || !orderId.equals(order.orderId())) {
                    throw new MatcherSafetyException(
                            "Blockchain gateway returned an order with unexpected identity"
                    );
                }
                orders.put(orderId, order);
            }
        }

        if (!ineligibleOrderIds.isEmpty()) {
            return MatchPreflightResult.ineligible(ineligibleOrderIds);
        }

        Map<BigInteger, BigInteger> cumulativeFills = new HashMap<>();
        Map<FundingKey, BigInteger> fundingRequired = new HashMap<>();
        Map<FundingKey, Set<BigInteger>> fundingOrders = new HashMap<>();

        for (MatchProposal leg : proposal.legs()) {
            OrderSnapshot buy = orders.get(leg.buyOrderId());
            OrderSnapshot sell = orders.get(leg.sellOrderId());
            validateOrder(leg, buy, OrderSide.BUY, ineligibleOrderIds);
            validateOrder(leg, sell, OrderSide.SELL, ineligibleOrderIds);
            accumulateFill(leg.buyOrderId(), leg.fillLots(), buy, cumulativeFills, ineligibleOrderIds);
            accumulateFill(leg.sellOrderId(), leg.fillLots(), sell, cumulativeFills, ineligibleOrderIds);

            if (buy.limitPriceQuoteAtomsPerLot().compareTo(
                    sell.limitPriceQuoteAtomsPerLot()
            ) < 0) {
                ineligibleOrderIds.add(leg.buyOrderId());
                ineligibleOrderIds.add(leg.sellOrderId());
                continue;
            }
            if (leg.executionPriceQuoteAtomsPerLot().compareTo(
                    sell.limitPriceQuoteAtomsPerLot()
            ) < 0 || leg.executionPriceQuoteAtomsPerLot().compareTo(
                    buy.limitPriceQuoteAtomsPerLot()
            ) > 0) {
                throw new MatcherSafetyException(
                        "Proposed execution price is outside the crossed price range"
                );
            }

            addFunding(
                    new FundingKey(market.baseTokenAddress(), sell.owner()),
                    leg.fillLots().multiply(market.baseLotSizeAtoms()),
                    leg.sellOrderId(),
                    fundingRequired,
                    fundingOrders
            );
            addFunding(
                    new FundingKey(market.quoteTokenAddress(), buy.owner()),
                    leg.fillLots().multiply(leg.executionPriceQuoteAtomsPerLot()),
                    leg.buyOrderId(),
                    fundingRequired,
                    fundingOrders
            );
        }

        for (Map.Entry<FundingKey, BigInteger> entry : fundingRequired.entrySet()) {
            checkFunding(
                    entry.getKey(),
                    identity.dexAddress(),
                    entry.getValue(),
                    fundingOrders.get(entry.getKey()),
                    ineligibleOrderIds
            );
        }

        if (!ineligibleOrderIds.isEmpty()) {
            return MatchPreflightResult.ineligible(ineligibleOrderIds);
        }

        return MatchPreflightResult.eligible(gateway.getExecutorAddress());
    }

    public MatchPreflightResult check(MatchProposal proposal) {
        return check(MatchBatch.single(proposal));
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

        if (!proposal.marketId().equals(order.marketId())
                || order.side() != expectedSide
                || !order.isMatchable()) {
            ineligibleOrderIds.add(expectedOrderId);
        }
    }

    private void checkFunding(
            FundingKey key,
            EthereumAddress spender,
            BigInteger requiredAmount,
            Set<BigInteger> affectedOrderIds,
            Set<BigInteger> ineligibleOrderIds
    ) {
        BigInteger balance = gateway.getTokenBalance(key.token(), key.owner());
        BigInteger allowance = gateway.getTokenAllowance(
                key.token(),
                key.owner(),
                spender
        );

        if (balance.compareTo(requiredAmount) < 0
                || allowance.compareTo(requiredAmount) < 0) {
            ineligibleOrderIds.addAll(affectedOrderIds);
        }
    }

    private void requireDeployment(
            MatchBatch proposal,
            ChainIdentity identity
    ) {
        if (!proposal.deploymentId().equals(identity.deploymentId())) {
            throw new MatcherSafetyException(
                    "Match proposal and blockchain gateway deployments differ"
            );
        }
    }

    private void requireMarketIdentity(
            MatchBatch proposal,
            MarketSnapshot market
    ) {
        if (!proposal.deploymentId().equals(market.deploymentId())
                || !proposal.marketId().equals(market.marketId())) {
            throw new MatcherSafetyException(
                    "Blockchain gateway returned a market with unexpected identity"
            );
        }
    }

    private MatchPreflightResult bothOrdersIneligible(MatchBatch proposal) {
        return MatchPreflightResult.ineligible(allOrderIds(proposal));
    }

    private Set<BigInteger> allOrderIds(MatchBatch batch) {
        Set<BigInteger> result = new HashSet<>();
        for (MatchProposal leg : batch.legs()) {
            result.add(leg.buyOrderId());
            result.add(leg.sellOrderId());
        }
        return Set.copyOf(result);
    }

    private void accumulateFill(
            BigInteger orderId,
            BigInteger fillLots,
            OrderSnapshot order,
            Map<BigInteger, BigInteger> cumulativeFills,
            Set<BigInteger> ineligibleOrderIds
    ) {
        BigInteger total = cumulativeFills.merge(
                orderId,
                fillLots,
                BigInteger::add
        );
        if (total.compareTo(order.remainingLots()) > 0) {
            ineligibleOrderIds.add(orderId);
        }
    }

    private void addFunding(
            FundingKey key,
            BigInteger amount,
            BigInteger orderId,
            Map<FundingKey, BigInteger> required,
            Map<FundingKey, Set<BigInteger>> affectedOrders
    ) {
        required.merge(key, amount, BigInteger::add);
        affectedOrders.computeIfAbsent(key, ignored -> new HashSet<>())
                .add(orderId);
    }

    private record FundingKey(EthereumAddress token, EthereumAddress owner) {
    }
}
