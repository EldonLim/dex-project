package com.dex.backend.api.trade;

import com.dex.backend.api.CursorPageResponse;
import com.dex.backend.api.error.ApiResourceNotFoundException;
import com.dex.backend.config.DexProperties;
import com.dex.backend.persistence.market.MarketEntityId;
import com.dex.backend.persistence.market.MarketRepository;
import com.dex.backend.persistence.trade.TradeEntity;
import com.dex.backend.persistence.trade.TradeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.List;

@Service
public class TradeQueryService {

    private final MarketRepository marketRepository;
    private final TradeRepository tradeRepository;
    private final TradeCursorCodec cursorCodec;
    private final String deploymentId;

    public TradeQueryService(
            MarketRepository marketRepository,
            TradeRepository tradeRepository,
            TradeCursorCodec cursorCodec,
            DexProperties properties
    ) {
        this.marketRepository = marketRepository;
        this.tradeRepository = tradeRepository;
        this.cursorCodec = cursorCodec;
        this.deploymentId = properties.blockchain().deploymentId();
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<TradeResponse> findTrades(
            BigInteger marketId,
            String cursor,
            int limit
    ) {
        if (!marketRepository.existsById(new MarketEntityId(deploymentId, marketId))) {
            throw new ApiResourceNotFoundException();
        }

        TradeCursorCodec.Position position = cursor == null
                ? null
                : cursorCodec.decode(cursor);

        List<TradeEntity> trades = tradeRepository.findRecentTrades(
                deploymentId,
                marketId,
                position == null ? null : position.blockNumber(),
                position == null ? null : position.transactionIndex(),
                position == null ? null : position.logIndex(),
                PageRequest.of(0, limit + 1)
        );

        boolean hasMore = trades.size() > limit;
        List<TradeEntity> pageTrades = trades.subList(0, Math.min(trades.size(), limit));
        String nextCursor = hasMore
                ? cursorCodec.encode(toPosition(pageTrades.getLast()))
                : null;

        return new CursorPageResponse<>(
                pageTrades.stream().map(this::toResponse).toList(),
                nextCursor
        );
    }

    private TradeCursorCodec.Position toPosition(TradeEntity trade) {
        return new TradeCursorCodec.Position(
                trade.getBlockNumber(),
                trade.getTransactionIndex(),
                trade.getId().getLogIndex()
        );
    }

    private TradeResponse toResponse(TradeEntity trade) {
        return new TradeResponse(
                trade.getTradeId() == null ? null : trade.getTradeId().toString(),
                trade.getMarketId().toString(),
                trade.getBuyOrderId().toString(),
                trade.getSellOrderId().toString(),
                trade.getFillLots().toString(),
                trade.getExecutionPriceQuoteAtomsPerLot().toString(),
                trade.getBuyOrderRemainingLots().toString(),
                trade.getSellOrderRemainingLots().toString(),
                new TradeExecutionPositionResponse(
                        trade.getBlockNumber().toString(),
                        trade.getTransactionIndex().toString(),
                        trade.getId().getLogIndex().toString()
                ),
                trade.getId().getTransactionHash().value(),
                trade.getBlockTimestamp().toString()
        );
    }
}
