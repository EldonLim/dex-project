package com.dex.backend.indexer.projection;

import com.dex.backend.blockchain.event.DexEvent;
import com.dex.backend.blockchain.event.EventMetadata;
import com.dex.backend.blockchain.event.MarketRegisteredEvent;
import com.dex.backend.blockchain.event.OrderCancelledEvent;
import com.dex.backend.blockchain.event.OrderPlacedEvent;
import com.dex.backend.blockchain.event.TradeExecutedEvent;
import com.dex.backend.blockchain.model.ChainPosition;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.order.OrderSide;
import com.dex.backend.order.OrderStatus;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.event.ProcessedEventEntity;
import com.dex.backend.persistence.event.ProcessedEventEntityId;
import com.dex.backend.persistence.event.ProcessedEventRepository;
import com.dex.backend.persistence.execution.ExecutionAttemptTransitionException;
import com.dex.backend.persistence.execution.IndexedTradeConfirmationService;
import com.dex.backend.persistence.market.MarketEntity;
import com.dex.backend.persistence.market.MarketEntityId;
import com.dex.backend.persistence.market.MarketRepository;
import com.dex.backend.persistence.order.OrderEntity;
import com.dex.backend.persistence.order.OrderEntityId;
import com.dex.backend.persistence.order.OrderRepository;
import com.dex.backend.persistence.trade.TradeEntity;
import com.dex.backend.persistence.trade.TradeEntityId;
import com.dex.backend.persistence.trade.TradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

@Service
public class EventProjectionService {

    private final ChainDeploymentRepository deploymentRepository;
    private final MarketRepository marketRepository;
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final IndexedTradeConfirmationService tradeConfirmationService;

    public EventProjectionService(
            ChainDeploymentRepository deploymentRepository,
            MarketRepository marketRepository,
            OrderRepository orderRepository,
            TradeRepository tradeRepository,
            ProcessedEventRepository processedEventRepository,
            IndexedTradeConfirmationService tradeConfirmationService
    ) {
        this.deploymentRepository = deploymentRepository;
        this.marketRepository = marketRepository;
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.processedEventRepository = processedEventRepository;
        this.tradeConfirmationService = tradeConfirmationService;
    }

    @Transactional
    public EventProjectionResult apply(DexEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        EventMetadata metadata = event.metadata();
        ProcessedEventEntityId eventId = eventId(metadata);
        Optional<ProcessedEventEntity> existingEvent = processedEventRepository.findById(eventId);

        if (existingEvent.isPresent()) {
            verifyDuplicateMetadata(existingEvent.orElseThrow(), event);
            return EventProjectionResult.ALREADY_PROCESSED;
        }

        ChainDeploymentEntity deployment = requireDeployment(metadata);
        verifyEventBelongsToDeployment(event, deployment);

        switch (event) {
            case MarketRegisteredEvent marketRegistered -> applyMarketRegistered(marketRegistered);
            case OrderPlacedEvent orderPlaced -> applyOrderPlaced(orderPlaced);
            case OrderCancelledEvent orderCancelled -> applyOrderCancelled(orderCancelled);
            case TradeExecutedEvent tradeExecuted -> applyTradeExecuted(tradeExecuted);
        }

        processedEventRepository.save(toProcessedEvent(event));
        return EventProjectionResult.APPLIED;
    }

    private void applyMarketRegistered(MarketRegisteredEvent event) {
        String deploymentId = event.metadata().deploymentId();
        MarketEntityId marketId = new MarketEntityId(deploymentId, event.marketId());

        if (marketRepository.existsById(marketId)) {
            throw projectionFailure(event, "Market already exists");
        }

        marketRepository.save(new MarketEntity(
                marketId,
                event.baseTokenAddress(),
                event.quoteTokenAddress(),
                event.baseSymbol(),
                event.quoteSymbol(),
                event.baseDecimals(),
                event.quoteDecimals(),
                event.baseLotSizeAtoms(),
                true
        ));
    }

    private void applyOrderPlaced(OrderPlacedEvent event) {
        String deploymentId = event.metadata().deploymentId();
        MarketEntityId marketId = new MarketEntityId(deploymentId, event.marketId());
        OrderEntityId orderId = new OrderEntityId(deploymentId, event.orderId());

        requireMarket(marketId, event);

        if (orderRepository.existsById(orderId)) {
            throw projectionFailure(event, "Order already exists");
        }

        EventMetadata metadata = event.metadata();
        ChainPosition position = metadata.position();

        orderRepository.save(new OrderEntity(
                orderId,
                event.marketId(),
                event.owner(),
                event.side(),
                event.limitPriceQuoteAtomsPerLot(),
                event.originalLots(),
                event.originalLots(),
                OrderStatus.OPEN,
                position.blockNumber(),
                position.transactionIndex(),
                position.logIndex(),
                metadata.transactionHash(),
                metadata.blockHash(),
                metadata.blockTimestamp()
        ));
    }

    private void applyOrderCancelled(OrderCancelledEvent event) {
        String deploymentId = event.metadata().deploymentId();
        OrderEntity order = requireOrder(
                new OrderEntityId(deploymentId, event.orderId()),
                event
        );

        if (!order.getMarketId().equals(event.marketId())) {
            throw projectionFailure(event, "Cancellation market does not match the order");
        }

        try {
            order.cancel(event.remainingLots());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw projectionFailure(event, exception.getMessage());
        }
    }

    private void applyTradeExecuted(TradeExecutedEvent event) {
        String deploymentId = event.metadata().deploymentId();
        requireMarket(new MarketEntityId(deploymentId, event.marketId()), event);

        OrderEntity buyOrder = requireOrder(
                new OrderEntityId(deploymentId, event.buyOrderId()),
                event
        );
        OrderEntity sellOrder = requireOrder(
                new OrderEntityId(deploymentId, event.sellOrderId()),
                event
        );

        verifyTradeOrders(event, buyOrder, sellOrder);

        try {
            buyOrder.validateFill(event.fillLots(), event.buyOrderRemainingLots());
            sellOrder.validateFill(event.fillLots(), event.sellOrderRemainingLots());
            buyOrder.applyFill(event.fillLots(), event.buyOrderRemainingLots());
            sellOrder.applyFill(event.fillLots(), event.sellOrderRemainingLots());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw projectionFailure(event, exception.getMessage());
        }

        EventMetadata metadata = event.metadata();
        ChainPosition position = metadata.position();
        TradeEntityId tradeId = new TradeEntityId(
                deploymentId,
                metadata.transactionHash(),
                position.logIndex()
        );

        if (tradeRepository.existsById(tradeId)) {
            throw projectionFailure(event, "Trade already exists without a processed-event marker");
        }

        tradeRepository.save(new TradeEntity(
                tradeId,
                event.tradeId(),
                event.marketId(),
                event.buyOrderId(),
                event.sellOrderId(),
                event.fillLots(),
                event.executionPriceQuoteAtomsPerLot(),
                event.buyOrderRemainingLots(),
                event.sellOrderRemainingLots(),
                position.blockNumber(),
                position.transactionIndex(),
                metadata.blockHash(),
                metadata.blockTimestamp()
        ));

        try {
            tradeConfirmationService.confirmMatchingAttempt(
                    deploymentId,
                    metadata.transactionHash(),
                    event.marketId(),
                    event.buyOrderId(),
                    event.sellOrderId(),
                    event.fillLots(),
                    event.executionPriceQuoteAtomsPerLot()
            );
        } catch (ExecutionAttemptTransitionException
                 | IllegalArgumentException exception) {
            throw projectionFailure(event, exception.getMessage());
        }
    }

    private void verifyTradeOrders(
            TradeExecutedEvent event,
            OrderEntity buyOrder,
            OrderEntity sellOrder
    ) {
        if (buyOrder.getSide() != OrderSide.BUY || sellOrder.getSide() != OrderSide.SELL) {
            throw projectionFailure(event, "Trade order sides are invalid");
        }

        if (!buyOrder.getMarketId().equals(event.marketId())
                || !sellOrder.getMarketId().equals(event.marketId())) {
            throw projectionFailure(event, "Trade orders do not belong to the event market");
        }
    }

    private ChainDeploymentEntity requireDeployment(EventMetadata metadata) {
        return deploymentRepository.findById(metadata.deploymentId())
                .orElseThrow(() -> new EventProjectionException(
                        "Cannot project event for unknown deployment " + metadata.deploymentId()
                ));
    }

    private void verifyEventBelongsToDeployment(
            DexEvent event,
            ChainDeploymentEntity deployment
    ) {
        EventMetadata metadata = event.metadata();
        EthereumAddress expectedContractAddress = switch (event) {
            case MarketRegisteredEvent ignored ->
                    deployment.getMarketRegistryAddress();
            case OrderPlacedEvent ignored -> deployment.getDexAddress();
            case OrderCancelledEvent ignored -> deployment.getDexAddress();
            case TradeExecutedEvent ignored -> deployment.getDexAddress();
        };

        if (!expectedContractAddress.equals(metadata.contractAddress())) {
            throw new EventProjectionException(
                    "Event contract address does not match deployment " + metadata.deploymentId()
            );
        }

        if (metadata.position().blockNumber().compareTo(deployment.getDeploymentBlockNumber()) < 0) {
            throw new EventProjectionException(
                    "Event block precedes deployment block for " + metadata.deploymentId()
            );
        }
    }

    private void verifyDuplicateMetadata(ProcessedEventEntity existing, DexEvent duplicate) {
        EventMetadata metadata = duplicate.metadata();
        ChainPosition position = metadata.position();

        boolean sameMetadata = existing.getContractAddress().equals(metadata.contractAddress())
                && existing.getEventName() == duplicate.type()
                && existing.getBlockNumber().equals(position.blockNumber())
                && existing.getBlockHash().equals(metadata.blockHash())
                && existing.getTransactionIndex().equals(position.transactionIndex());

        if (!sameMetadata) {
            throw projectionFailure(
                    duplicate,
                    "Event identity was already processed with different metadata"
            );
        }
    }

    private MarketEntity requireMarket(MarketEntityId id, DexEvent event) {
        return marketRepository.findById(id)
                .orElseThrow(() -> projectionFailure(event, "Referenced market does not exist"));
    }

    private OrderEntity requireOrder(OrderEntityId id, DexEvent event) {
        return orderRepository.findById(id)
                .orElseThrow(() -> projectionFailure(event, "Referenced order does not exist"));
    }

    private ProcessedEventEntity toProcessedEvent(DexEvent event) {
        EventMetadata metadata = event.metadata();
        ChainPosition position = metadata.position();

        return new ProcessedEventEntity(
                eventId(metadata),
                metadata.contractAddress(),
                event.type(),
                position.blockNumber(),
                metadata.blockHash(),
                position.transactionIndex()
        );
    }

    private ProcessedEventEntityId eventId(EventMetadata metadata) {
        return new ProcessedEventEntityId(
                metadata.deploymentId(),
                metadata.transactionHash(),
                metadata.position().logIndex()
        );
    }

    private EventProjectionException projectionFailure(DexEvent event, String detail) {
        EventMetadata metadata = event.metadata();

        return new EventProjectionException(
                "Failed to project " + event.type()
                        + " at " + metadata.position()
                        + " for deployment " + metadata.deploymentId()
                        + ": " + detail
        );
    }
}
