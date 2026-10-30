package com.dex.backend.blockchain.ethereum;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.blockchain.BlockchainGatewayException;
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
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import org.springframework.util.StringUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.request.Transaction;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete BlockchainGateway for the checked-in Ethereum contracts. */
public final class EthereumBlockchainGateway implements BlockchainGateway {

    private static final BigInteger MINIMUM_GAS_MARGIN =
            BigInteger.valueOf(10_000);
    private static final BigInteger MAXIMUM_GAS_MARGIN =
            BigInteger.valueOf(100_000);

    private final EthereumRpcClient rpcClient;
    private final EthereumDexClient dexClient;
    private final String deploymentId;
    private final EthereumAddress orderBookAddress;
    private final EthereumSettlementSigner signer;

    EthereumBlockchainGateway(
            EthereumRpcClient rpcClient,
            EthereumDexClient dexClient,
            String deploymentId,
            EthereumAddress orderBookAddress,
            EthereumSettlementSigner signer
    ) {
        this.rpcClient = Objects.requireNonNull(
                rpcClient,
                "rpcClient must not be null"
        );
        this.dexClient = Objects.requireNonNull(
                dexClient,
                "dexClient must not be null"
        );
        this.deploymentId = requireDeploymentId(deploymentId);
        this.orderBookAddress = Objects.requireNonNull(
                orderBookAddress,
                "orderBookAddress must not be null"
        );
        this.signer = signer;
    }

    static EthereumBlockchainGateway fromProperties(
            Web3j web3j,
            DexProperties properties
    ) {
        Objects.requireNonNull(web3j, "web3j must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        DexProperties.Blockchain blockchain = properties.blockchain();
        if (blockchain.mode() != BlockchainMode.ETHEREUM) {
            throw new IllegalArgumentException(
                    "Ethereum gateway requires ETHEREUM blockchain mode"
            );
        }

        EthereumAddress orderBook = new EthereumAddress(
                blockchain.dexAddress()
        );
        EthereumAddress marketRegistry = new EthereumAddress(
                blockchain.marketRegistryAddress()
        );
        EthereumRpcClient rpcClient = EthereumRpcClient.fromProperties(
                web3j,
                properties
        );
        EthereumDexClient dexClient = new EthereumDexClient(
                rpcClient,
                blockchain.deploymentId(),
                orderBook,
                marketRegistry
        );

        EthereumSettlementSigner signer = null;
        if (StringUtils.hasText(properties.executor().privateKey())) {
            signer = new EthereumSettlementSigner(
                    blockchain.expectedChainId(),
                    orderBook,
                    properties.executor().privateKey()
            );
        }

        return new EthereumBlockchainGateway(
                rpcClient,
                dexClient,
                blockchain.deploymentId(),
                orderBook,
                signer
        );
    }

    @Override
    public ChainIdentity getChainIdentity() {
        return rpcClient.getChainIdentity();
    }

    @Override
    public BlockReference getLatestBlock() {
        return rpcClient.getLatestBlock();
    }

    @Override
    public Optional<BlockReference> getBlock(BigInteger blockNumber) {
        return rpcClient.getBlock(blockNumber);
    }

    @Override
    public List<DexEvent> getEvents(BlockRange range) {
        return dexClient.getEvents(range);
    }

    @Override
    public Optional<MarketSnapshot> getMarket(BigInteger marketId) {
        return dexClient.getMarket(marketId);
    }

    @Override
    public Optional<OrderSnapshot> getOrder(BigInteger orderId) {
        return dexClient.getOrder(orderId);
    }

    @Override
    public BigInteger getTokenBalance(
            EthereumAddress token,
            EthereumAddress owner
    ) {
        return rpcClient.getTokenBalance(token, owner);
    }

    @Override
    public BigInteger getTokenAllowance(
            EthereumAddress token,
            EthereumAddress owner,
            EthereumAddress spender
    ) {
        return rpcClient.getTokenAllowance(token, owner, spender);
    }

    @Override
    public EthereumAddress getExecutorAddress() {
        return requireSigner().executorAddress();
    }

    @Override
    public BigInteger getPendingExecutorNonce() {
        requireSigner();
        return rpcClient.getPendingExecutorNonce();
    }

    @Override
    public PreparedSettlement prepareSettlement(SettlementRequest request) {
        requireRequest(request);
        EthereumSettlementSigner activeSigner = requireSigner();
        BigInteger nonce = rpcClient.getPendingExecutorNonce();
        BigInteger gasPrice = rpcClient.getGasPrice();
        String data = activeSigner.encodeSettlement(request);
        Transaction estimateRequest = new Transaction(
                activeSigner.executorAddress().value(),
                nonce,
                gasPrice,
                null,
                orderBookAddress.value(),
                BigInteger.ZERO,
                data
        );
        BigInteger gasLimit = addGasMargin(
                rpcClient.estimateGas(estimateRequest)
        );

        try {
            return activeSigner.sign(
                    request,
                    nonce,
                    gasPrice,
                    gasLimit
            );
        } catch (RuntimeException exception) {
            throw new BlockchainGatewayException(
                    "Ethereum settlement signing failed",
                    false
            );
        }
    }

    @Override
    public Hash256 broadcastSettlement(
            PreparedSettlement preparedSettlement
    ) {
        Objects.requireNonNull(
                preparedSettlement,
                "preparedSettlement must not be null"
        );
        requireRequest(preparedSettlement.request());
        EthereumSettlementSigner activeSigner = requireSigner();
        if (!activeSigner.executorAddress().equals(
                preparedSettlement.executorAddress()
        )) {
            throw new IllegalArgumentException(
                    "Prepared settlement executor does not match configuration"
            );
        }
        return rpcClient.broadcastRawTransaction(
                preparedSettlement.signedTransactionHex(),
                preparedSettlement.transactionHash()
        );
    }

    @Override
    public TransactionObservation getTransaction(Hash256 transactionHash) {
        return rpcClient.getTransaction(transactionHash);
    }

    private EthereumSettlementSigner requireSigner() {
        if (signer == null) {
            throw new IllegalStateException(
                    "Executor credentials are not configured"
            );
        }
        return signer;
    }

    private void requireRequest(SettlementRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!deploymentId.equals(request.deploymentId())) {
            throw new IllegalArgumentException(
                    "Settlement request belongs to another deployment"
            );
        }
    }

    private BigInteger addGasMargin(BigInteger estimate) {
        Objects.requireNonNull(estimate, "estimate must not be null");
        if (estimate.signum() <= 0) {
            throw new IllegalArgumentException("estimate must be positive");
        }
        BigInteger margin = estimate.divide(BigInteger.valueOf(5))
                .max(MINIMUM_GAS_MARGIN)
                .min(MAXIMUM_GAS_MARGIN);
        return estimate.add(margin);
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
