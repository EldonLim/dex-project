package com.dex.backend.matcher;

import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.config.DexProperties;
import com.dex.backend.market.Market;
import com.dex.backend.order.Order;
import com.dex.backend.persistence.execution.ExecutionAttemptLegRepository;
import com.dex.backend.persistence.execution.ExecutionAttemptStatus;
import com.dex.backend.persistence.execution.ReservedOrderPair;
import com.dex.backend.persistence.market.MarketEntity;
import com.dex.backend.persistence.market.MarketRepository;
import com.dex.backend.persistence.order.OrderEntity;
import com.dex.backend.persistence.order.OrderRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class ProjectionMatchService {

    private final MarketRepository marketRepository;
    private final OrderRepository orderRepository;
    private final ExecutionAttemptLegRepository executionAttemptLegRepository;
    private final OrderMatcher orderMatcher;
    private final TemporaryOrderIneligibilityRegistry ineligibilityRegistry;
    private final String deploymentId;
    private final int maxBatchLegs;

    public ProjectionMatchService(
            MarketRepository marketRepository,
            OrderRepository orderRepository,
            ExecutionAttemptLegRepository executionAttemptLegRepository,
            OrderMatcher orderMatcher,
            TemporaryOrderIneligibilityRegistry ineligibilityRegistry,
            DexProperties properties
    ) {
        this.marketRepository = marketRepository;
        this.orderRepository = orderRepository;
        this.executionAttemptLegRepository = executionAttemptLegRepository;
        this.orderMatcher = orderMatcher;
        this.ineligibilityRegistry = ineligibilityRegistry;
        this.deploymentId = properties.blockchain().deploymentId();
        this.maxBatchLegs = properties.matcher().maxBatchLegs();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<MatchProposal> findNextProposal() {
        return findNextBatch().map(batch -> batch.legs().getFirst());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<MatchBatch> findNextBatch() {
        Set<BigInteger> excludedOrderIds = loadExcludedOrderIds();
        List<MarketEntity> markets = marketRepository
                .findAllByDeploymentIdOrderByMarketId(deploymentId);

        for (MarketEntity marketEntity : markets) {
            if (!marketEntity.isActive()) {
                continue;
            }

            Market market = toDomain(marketEntity);
            List<Order> orders = loadEligibleOrders(
                    marketEntity,
                    excludedOrderIds
            );
            Optional<MatchBatch> proposal = orderMatcher.findNextBatch(
                    market,
                    orders,
                    maxBatchLegs
            );

            if (proposal.isPresent()) {
                return proposal;
            }
        }

        return Optional.empty();
    }

    private Set<BigInteger> loadExcludedOrderIds() {
        Set<BigInteger> excludedOrderIds = new HashSet<>(
                ineligibilityRegistry.activeOrderIds(deploymentId)
        );

        for (ReservedOrderPair pair : executionAttemptLegRepository
                .findReservedOrderPairs(
                        deploymentId,
                        ExecutionAttemptStatus.reservingStatuses()
                )) {
            excludedOrderIds.add(pair.getBuyOrderId());
            excludedOrderIds.add(pair.getSellOrderId());
        }

        return Set.copyOf(excludedOrderIds);
    }

    private List<Order> loadEligibleOrders(
            MarketEntity market,
            Set<BigInteger> excludedOrderIds
    ) {
        List<OrderEntity> bids = orderRepository.findEligibleBids(
                deploymentId,
                market.getId().getMarketId(),
                Pageable.unpaged()
        );
        List<OrderEntity> asks = orderRepository.findEligibleAsks(
                deploymentId,
                market.getId().getMarketId(),
                Pageable.unpaged()
        );
        List<Order> orders = new ArrayList<>(bids.size() + asks.size());

        bids.stream()
                .filter(order -> !excludedOrderIds.contains(
                        order.getId().getOrderId()
                ))
                .map(this::toDomain)
                .forEach(orders::add);
        asks.stream()
                .filter(order -> !excludedOrderIds.contains(
                        order.getId().getOrderId()
                ))
                .map(this::toDomain)
                .forEach(orders::add);

        return List.copyOf(orders);
    }

    private Market toDomain(MarketEntity market) {
        return new Market(
                market.getId().getDeploymentId(),
                market.getId().getMarketId(),
                market.getBaseTokenAddress(),
                market.getQuoteTokenAddress(),
                market.getBaseSymbol(),
                market.getQuoteSymbol(),
                market.getBaseDecimals(),
                market.getQuoteDecimals(),
                market.getBaseLotSizeAtoms(),
                market.isActive()
        );
    }

    private Order toDomain(OrderEntity order) {
        return new Order(
                order.getId().getDeploymentId(),
                order.getId().getOrderId(),
                order.getMarketId(),
                order.getOwnerAddress(),
                order.getSide(),
                order.getLimitPriceQuoteAtomsPerLot(),
                order.getOriginalLots(),
                order.getRemainingLots(),
                order.getStatus(),
                new ChainPosition(
                        order.getCreationBlockNumber(),
                        order.getCreationTransactionIndex(),
                        order.getCreationLogIndex()
                ),
                order.getCreationTransactionHash(),
                order.getCreationBlockHash(),
                order.getBlockTimestamp()
        );
    }
}
