package com.dex.backend.blockchain;

import com.dex.backend.blockchain.event.DexEvent;
import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.SettlementRequest;
import com.dex.backend.blockchain.execution.TransactionObservation;
import com.dex.backend.blockchain.model.BlockRange;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.blockchain.model.ChainIdentity;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.blockchain.model.MarketSnapshot;
import com.dex.backend.blockchain.model.OrderSnapshot;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Blocking access to the blockchain for one configured DEX deployment.
 *
 * <p>Methods never return {@code null}. Missing blocks, markets, and orders
 * are represented by {@link Optional#empty()}.
 *
 * <p>Arguments must be non-null. Numeric identifiers and block numbers must
 * be non-negative. Invalid requests supplied by backend code are rejected
 * with {@link IllegalArgumentException}.
 *
 * <p>Implementation failures, including RPC, decoding, signing, and simulated
 * adapter failures, are reported as {@link BlockchainGatewayException}.
 * Exception messages must not contain private keys or signed transaction bytes.
 *
 * <p>Ethereum implementations must never silently switch to mock behavior.
 *
 * <p>Methods are synchronous and may block. Polling workers should invoke
 * them from controlled background threads.
 */
public interface BlockchainGateway {

    /**
     * Returns the identity of the configured DEX deployment.
     *
     * @return the non-null deployment identity
     */
    ChainIdentity getChainIdentity();

    /**
     * Returns the latest block observed by this gateway.
     *
     * @return the non-null latest block reference
     */
    BlockReference getLatestBlock();

    /**
     * Looks up a block by its non-negative number.
     *
     * @param blockNumber the block number to look up
     * @return the block, or an empty optional if it is absent
     */
    Optional<BlockReference> getBlock(BigInteger blockNumber);

    /**
     * Fetches normalized DEX events for the configured deployment.
     *
     * <p>Both boundaries of {@code range} are inclusive. Events are returned
     * in ascending {@code ChainPosition} order: block number, transaction
     * index, then log index. Returned events and their metadata are non-null.
     *
     * @param range the inclusive block range to query
     * @return matching events, or an empty immutable list if none are found;
     *         never {@code null}
     */
    List<DexEvent> getEvents(BlockRange range);

    /**
     * Reads current contract state for a market in the configured deployment.
     *
     * @param marketId the non-negative market identifier
     * @return the market snapshot, or an empty optional if it is absent
     */
    Optional<MarketSnapshot> getMarket(BigInteger marketId);

    /**
     * Reads current contract state for an order in the configured deployment.
     *
     * @param orderId the non-negative order identifier
     * @return the order snapshot, or an empty optional if it is absent
     */
    Optional<OrderSnapshot> getOrder(BigInteger orderId);

    /**
     * Reads an owner's token balance in the token's smallest units.
     *
     * @param token the token contract address
     * @param owner the account whose balance is requested
     * @return a non-null, non-negative balance
     */
    BigInteger getTokenBalance(
            EthereumAddress token,
            EthereumAddress owner
    );

    /**
     * Reads the token amount a spender is authorized to spend for an owner.
     *
     * @param token the token contract address
     * @param owner the account that granted the allowance
     * @param spender the account authorized to spend
     * @return a non-null, non-negative allowance in token atoms
     */
    BigInteger getTokenAllowance(
            EthereumAddress token,
            EthereumAddress owner,
            EthereumAddress spender
    );

    /**
     * Returns the account used to sign settlement transactions.
     *
     * @return the non-null executor address
     */
    EthereumAddress getExecutorAddress();

    /**
     * Reads the executor's transaction nonce using pending transaction state.
     *
     * <p>This query does not itself reserve a nonce. Callers must coordinate
     * concurrent settlement preparation.
     *
     * @return a non-null, non-negative nonce
     */
    BigInteger getPendingExecutorNonce();

    /**
     * Prepares and signs a settlement transaction without broadcasting it.
     *
     * <p>The request must belong to the configured deployment. The returned
     * value includes the executor, nonce, transaction hash, and signed bytes,
     * allowing transaction identity to be recorded before network submission.
     *
     * @param request the settlement to prepare
     * @return the non-null prepared transaction
     * @throws IllegalArgumentException if the request is invalid for this gateway
     * @throws BlockchainGatewayException if preparation or signing fails
     */
    PreparedSettlement prepareSettlement(SettlementRequest request);

    /**
     * Broadcasts an already prepared settlement transaction.
     *
     * <p>The returned hash must equal the transaction hash contained in
     * {@code preparedSettlement}. This method must not replace the prepared
     * transaction with a newly signed transaction.
     *
     * <p>Successful submission does not establish successful execution.
     * A submission failure may leave the network outcome uncertain; callers
     * can query the prepared transaction's known hash to reconcile its state.
     *
     * @param preparedSettlement the prepared transaction to submit
     * @return the same non-null hash carried by the prepared transaction
     * @throws IllegalArgumentException if the prepared settlement is invalid
     *         for this gateway
     * @throws BlockchainGatewayException if broadcasting fails
     */
    Hash256 broadcastSettlement(PreparedSettlement preparedSettlement);

    /**
     * Observes a transaction's current state.
     *
     * <p>Distinguishes unknown ({@code NOT_FOUND}), pending ({@code PENDING}),
     * successfully executed ({@code SUCCEEDED}), and reverted
     * ({@code REVERTED}) transactions.
     *
     * <p>An unknown transaction is represented by a {@code NOT_FOUND}
     * observation, never {@code null}. A lookup failure throws
     * {@link BlockchainGatewayException}; it must not be reported as
     * {@code NOT_FOUND}.
     *
     * @param transactionHash the transaction to look up
     * @return a non-null observation for the requested hash
     */
    TransactionObservation getTransaction(Hash256 transactionHash);
}