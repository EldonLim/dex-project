package com.dex.backend.blockchain.ethereum;

import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.blockchain.execution.TransactionObservation;
import com.dex.backend.blockchain.model.BlockRange;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.blockchain.model.ChainIdentity;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import org.springframework.util.StringUtils;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.EthTransaction;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.io.IOException;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * ABI-independent Ethereum JSON-RPC operations used by the future gateway.
 *
 * <p>This class deliberately does not implement {@code BlockchainGateway}.
 * DEX event decoding, contract state reads, and settlement encoding require
 * the finalized Solidity ABI.
 */
public final class EthereumRpcClient {

    private static final Pattern SIGNED_TRANSACTION =
            Pattern.compile("0x(?:[0-9a-fA-F]{2})+");

    private static final List<TypeReference<?>> UINT256_OUTPUT =
            List.of(TypeReference.create(Uint256.class));

    private final Web3j web3j;
    private final String deploymentId;
    private final BigInteger expectedChainId;
    private final EthereumAddress dexAddress;
    private final EthereumAddress marketRegistryAddress;
    private final BigInteger deploymentBlockNumber;
    private final EthereumAddress executorAddress;

    public EthereumRpcClient(
            Web3j web3j,
            String deploymentId,
            BigInteger expectedChainId,
            EthereumAddress dexAddress,
            EthereumAddress marketRegistryAddress,
            BigInteger deploymentBlockNumber,
            EthereumAddress executorAddress
    ) {
        this.web3j = Objects.requireNonNull(web3j, "web3j must not be null");
        this.deploymentId = requireDeploymentId(deploymentId);
        this.expectedChainId = requirePositive(
                expectedChainId,
                "expectedChainId"
        );
        this.dexAddress = Objects.requireNonNull(
                dexAddress,
                "dexAddress must not be null"
        );
        this.marketRegistryAddress = Objects.requireNonNull(
                marketRegistryAddress,
                "marketRegistryAddress must not be null"
        );
        this.deploymentBlockNumber = requireNonNegative(
                deploymentBlockNumber,
                "deploymentBlockNumber"
        );
        this.executorAddress = executorAddress;
    }

    /**
     * Creates a client from validated application properties.
     *
     * <p>When a private key is configured, it is used only to derive the
     * executor address and is not retained by this client.
     */
    public static EthereumRpcClient fromProperties(
            Web3j web3j,
            DexProperties properties
    ) {
        Objects.requireNonNull(properties, "properties must not be null");
        DexProperties.Blockchain blockchain = properties.blockchain();

        if (blockchain.mode() != BlockchainMode.ETHEREUM) {
            throw new IllegalArgumentException(
                    "Ethereum RPC client requires ETHEREUM blockchain mode"
            );
        }

        String privateKey = properties.executor().privateKey();
        EthereumAddress executor = null;

        if (StringUtils.hasText(privateKey)) {
            try {
                executor = new EthereumAddress(
                        Credentials.create(privateKey).getAddress()
                );
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(
                        "Configured executor private key is invalid"
                );
            }
        }

        return new EthereumRpcClient(
                web3j,
                blockchain.deploymentId(),
                blockchain.expectedChainId(),
                new EthereumAddress(blockchain.dexAddress()),
                new EthereumAddress(blockchain.marketRegistryAddress()),
                blockchain.deploymentBlock(),
                executor
        );
    }

    public ChainIdentity getChainIdentity() {
        EthChainId response = send("chain identity", web3j.ethChainId());
        final BigInteger actualChainId;
        try {
            actualChainId = requireResponseValue(
                    response.getChainId(),
                    "chain ID"
            );
        } catch (BlockchainGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw nonRetryable("Ethereum response contains invalid chain ID");
        }

        if (!expectedChainId.equals(actualChainId)) {
            throw nonRetryable("Ethereum chain ID does not match configuration");
        }

        BlockReference deploymentBlock = getBlock(deploymentBlockNumber)
                .orElseThrow(() -> nonRetryable(
                        "Configured deployment block does not exist"
                ));

        return new ChainIdentity(
                deploymentId,
                actualChainId,
                dexAddress,
                marketRegistryAddress,
                deploymentBlock.number(),
                deploymentBlock.hash()
        );
    }

    public BlockReference getLatestBlock() {
        EthBlock response = send(
                "latest block",
                web3j.ethGetBlockByNumber(
                        DefaultBlockParameterName.LATEST,
                        false
                )
        );

        if (response.getBlock() == null) {
            throw nonRetryable("Latest Ethereum block response is missing");
        }

        return mapBlock(response.getBlock(), null);
    }

    public Optional<BlockReference> getBlock(BigInteger blockNumber) {
        requireNonNegative(blockNumber, "blockNumber");
        EthBlock response = send(
                "block lookup",
                web3j.ethGetBlockByNumber(
                        DefaultBlockParameter.valueOf(blockNumber),
                        false
                )
        );

        if (response.getBlock() == null) {
            return Optional.empty();
        }

        return Optional.of(mapBlock(response.getBlock(), blockNumber));
    }

    public BigInteger getTokenBalance(
            EthereumAddress token,
            EthereumAddress owner
    ) {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        return callUint256(
                token,
                new Function(
                        "balanceOf",
                        List.of(new Address(owner.value())),
                        UINT256_OUTPUT
                ),
                "token balance"
        );
    }

    public BigInteger getTokenAllowance(
            EthereumAddress token,
            EthereumAddress owner,
            EthereumAddress spender
    ) {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(spender, "spender must not be null");

        return callUint256(
                token,
                new Function(
                        "allowance",
                        List.of(
                                new Address(owner.value()),
                                new Address(spender.value())
                        ),
                        UINT256_OUTPUT
                ),
                "token allowance"
        );
    }

    List<Log> getLogs(
            BlockRange range,
            List<EthereumAddress> contractAddresses
    ) {
        Objects.requireNonNull(range, "range must not be null");
        Objects.requireNonNull(
                contractAddresses,
                "contractAddresses must not be null"
        );
        if (contractAddresses.isEmpty()
                || contractAddresses.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "contractAddresses must contain at least one address"
            );
        }

        EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(range.fromInclusive()),
                DefaultBlockParameter.valueOf(range.toInclusive()),
                contractAddresses.stream()
                        .map(EthereumAddress::value)
                        .toList()
        );
        EthLog response = send("DEX event logs", web3j.ethGetLogs(filter));

        if (response.getLogs() == null) {
            throw nonRetryable("Ethereum log response is missing");
        }

        List<Log> logs = new ArrayList<>(response.getLogs().size());
        for (EthLog.LogResult<?> result : response.getLogs()) {
            if (result == null || !(result.get() instanceof Log log)) {
                throw nonRetryable("Ethereum log response is malformed");
            }
            logs.add(log);
        }
        return List.copyOf(logs);
    }

    Optional<List<Type>> callContract(
            EthereumAddress contract,
            Function function,
            DefaultBlockParameter block,
            String operation,
            boolean revertMeansMissing
    ) {
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(function, "function must not be null");
        Objects.requireNonNull(block, "block must not be null");
        if (!StringUtils.hasText(operation)) {
            throw new IllegalArgumentException("operation must not be blank");
        }

        Transaction call = Transaction.createEthCallTransaction(
                null,
                contract.value(),
                FunctionEncoder.encode(function)
        );
        EthCall response = sendContractCall(
                operation,
                web3j.ethCall(call, block),
                revertMeansMissing
        );

        if (response.isReverted()) {
            if (revertMeansMissing) {
                return Optional.empty();
            }
            throw nonRetryable("Ethereum contract call reverted");
        }

        if (!StringUtils.hasText(response.getValue())) {
            throw nonRetryable("Ethereum contract call returned no value");
        }

        try {
            return Optional.of(List.copyOf(FunctionReturnDecoder.decode(
                    response.getValue(),
                    function.getOutputParameters()
            )));
        } catch (RuntimeException exception) {
            throw nonRetryable(
                    "Ethereum contract call returned malformed data"
            );
        }
    }

    public Optional<EthereumAddress> getExecutorAddress() {
        return Optional.ofNullable(executorAddress);
    }

    public BigInteger getPendingExecutorNonce() {
        if (executorAddress == null) {
            throw new IllegalStateException(
                    "Executor credentials are not configured"
            );
        }

        EthGetTransactionCount response = send(
                "executor nonce",
                web3j.ethGetTransactionCount(
                        executorAddress.value(),
                        DefaultBlockParameterName.PENDING
                )
        );

        try {
            return requireNonNegativeResponse(
                    response.getTransactionCount(),
                    "executor nonce"
            );
        } catch (BlockchainGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw nonRetryable(
                    "Ethereum response contains invalid executor nonce"
            );
        }
    }

    public BigInteger getGasPrice() {
        EthGasPrice response = send("gas price", web3j.ethGasPrice());
        try {
            return requireNonNegativeResponse(
                    response.getGasPrice(),
                    "gas price"
            );
        } catch (BlockchainGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw nonRetryable(
                    "Ethereum response contains invalid gas price"
            );
        }
    }

    public BigInteger estimateGas(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        EthEstimateGas response = send(
                "settlement gas estimate",
                web3j.ethEstimateGas(transaction)
        );
        try {
            BigInteger estimate = requireNonNegativeResponse(
                    response.getAmountUsed(),
                    "gas estimate"
            );
            if (estimate.signum() == 0) {
                throw nonRetryable(
                        "Ethereum response contains invalid gas estimate"
                );
            }
            return estimate;
        } catch (BlockchainGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw nonRetryable(
                    "Ethereum response contains invalid gas estimate"
            );
        }
    }

    public Hash256 broadcastRawTransaction(
            String signedTransactionHex,
            Hash256 expectedTransactionHash
    ) {
        Objects.requireNonNull(
                signedTransactionHex,
                "signedTransactionHex must not be null"
        );
        Objects.requireNonNull(
                expectedTransactionHash,
                "expectedTransactionHash must not be null"
        );

        if (!SIGNED_TRANSACTION.matcher(signedTransactionHex).matches()) {
            throw new IllegalArgumentException(
                    "signedTransactionHex must contain complete hexadecimal bytes"
            );
        }

        String normalizedPayload = signedTransactionHex.toLowerCase(Locale.ROOT);
        Hash256 calculatedHash = new Hash256(Hash.sha3(normalizedPayload));

        if (!calculatedHash.equals(expectedTransactionHash)) {
            throw new IllegalArgumentException(
                    "Signed transaction does not match its expected hash"
            );
        }

        EthSendTransaction response = send(
                "raw transaction broadcast",
                web3j.ethSendRawTransaction(normalizedPayload)
        );
        Hash256 returnedHash = mapHash(
                response.getTransactionHash(),
                "broadcast transaction hash"
        );

        if (!returnedHash.equals(expectedTransactionHash)) {
            throw nonRetryable(
                    "Ethereum node returned an unexpected transaction hash"
            );
        }

        return returnedHash;
    }

    public TransactionObservation getTransaction(Hash256 transactionHash) {
        Objects.requireNonNull(
                transactionHash,
                "transactionHash must not be null"
        );

        EthGetTransactionReceipt receiptResponse = send(
                "transaction receipt",
                web3j.ethGetTransactionReceipt(transactionHash.value())
        );
        Optional<TransactionReceipt> receipt =
                receiptResponse.getTransactionReceipt();

        if (receipt.isPresent()) {
            return mapReceipt(transactionHash, receipt.orElseThrow());
        }

        EthTransaction transactionResponse = send(
                "transaction lookup",
                web3j.ethGetTransactionByHash(transactionHash.value())
        );

        if (transactionResponse.getTransaction().isEmpty()) {
            return TransactionObservation.notFound(transactionHash);
        }

        Hash256 returnedHash = mapHash(
                transactionResponse.getTransaction().orElseThrow().getHash(),
                "looked-up transaction hash"
        );

        if (!returnedHash.equals(transactionHash)) {
            throw nonRetryable(
                    "Ethereum node returned an unexpected transaction identity"
            );
        }

        return TransactionObservation.pending(transactionHash);
    }

    private TransactionObservation mapReceipt(
            Hash256 expectedHash,
            TransactionReceipt receipt
    ) {
        Hash256 actualHash = mapHash(
                receipt.getTransactionHash(),
                "receipt transaction hash"
        );

        if (!actualHash.equals(expectedHash)) {
            throw nonRetryable(
                    "Ethereum receipt has an unexpected transaction identity"
            );
        }

        final BigInteger blockNumber;
        try {
            blockNumber = requireNonNegativeResponse(
                    receipt.getBlockNumber(),
                    "receipt block number"
            );
        } catch (BlockchainGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw nonRetryable(
                    "Ethereum response contains invalid receipt block number"
            );
        }
        Hash256 blockHash = mapHash(receipt.getBlockHash(), "receipt block hash");
        String status = receipt.getStatus();

        if ("0x1".equalsIgnoreCase(status)) {
            return TransactionObservation.succeeded(
                    expectedHash,
                    blockNumber,
                    blockHash
            );
        }

        if ("0x0".equalsIgnoreCase(status)) {
            return TransactionObservation.reverted(
                    expectedHash,
                    blockNumber,
                    blockHash,
                    "Ethereum transaction reverted"
            );
        }

        throw nonRetryable("Ethereum receipt has an invalid status");
    }

    private BigInteger callUint256(
            EthereumAddress contract,
            Function function,
            String operation
    ) {
        List<Type> decoded = callContract(
                contract,
                function,
                DefaultBlockParameterName.LATEST,
                operation,
                false
        ).orElseThrow();

        if (decoded.size() != 1 || !(decoded.getFirst() instanceof Uint256 uint)) {
            throw nonRetryable("Ethereum contract call returned malformed data");
        }

        return requireNonNegativeResponse(uint.getValue(), operation);
    }

    private BlockReference mapBlock(
            EthBlock.Block block,
            BigInteger expectedNumber
    ) {
        final BigInteger number;
        final BigInteger timestamp;
        final Hash256 hash;

        try {
            number = requireNonNegativeResponse(block.getNumber(), "block number");
            timestamp = requireNonNegativeResponse(
                    block.getTimestamp(),
                    "block timestamp"
            );
            hash = mapHash(block.getHash(), "block hash");
        } catch (RuntimeException exception) {
            if (exception instanceof BlockchainGatewayException gatewayException) {
                throw gatewayException;
            }
            throw nonRetryable("Ethereum block response is malformed");
        }

        if (expectedNumber != null && !expectedNumber.equals(number)) {
            throw nonRetryable("Ethereum node returned the wrong block");
        }

        try {
            return new BlockReference(
                    number,
                    hash,
                    Instant.ofEpochSecond(timestamp.longValueExact())
            );
        } catch (ArithmeticException | DateTimeException exception) {
            throw nonRetryable("Ethereum block timestamp is invalid");
        }
    }

    private Hash256 mapHash(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw nonRetryable("Ethereum response is missing " + fieldName);
        }

        try {
            return new Hash256(value);
        } catch (IllegalArgumentException exception) {
            throw nonRetryable(
                    "Ethereum response contains invalid " + fieldName
            );
        }
    }

    private <T extends Response<?>> T send(
            String operation,
            Request<?, T> request
    ) {
        final T response;

        try {
            response = request.send();
        } catch (IOException exception) {
            throw retryable("Ethereum RPC request failed: " + operation);
        } catch (RuntimeException exception) {
            throw retryable("Ethereum RPC client failed: " + operation);
        }

        if (response == null) {
            throw nonRetryable("Ethereum RPC returned no response: " + operation);
        }

        if (response.hasError()) {
            boolean retryable = isRetryableRpcCode(response.getError().getCode());
            throw new BlockchainGatewayException(
                    "Ethereum RPC rejected request: " + operation,
                    retryable
            );
        }

        return response;
    }

    private EthCall sendContractCall(
            String operation,
            Request<?, EthCall> request,
            boolean allowRevert
    ) {
        final EthCall response;

        try {
            response = request.send();
        } catch (IOException exception) {
            throw retryable("Ethereum RPC request failed: " + operation);
        } catch (RuntimeException exception) {
            throw retryable("Ethereum RPC client failed: " + operation);
        }

        if (response == null) {
            throw nonRetryable("Ethereum RPC returned no response: " + operation);
        }

        if (response.hasError()
                && !(allowRevert && response.isReverted())) {
            boolean retryable = isRetryableRpcCode(response.getError().getCode());
            throw new BlockchainGatewayException(
                    "Ethereum RPC rejected request: " + operation,
                    retryable
            );
        }

        return response;
    }

    private boolean isRetryableRpcCode(int code) {
        return code == -32603 || (code <= -32000 && code >= -32099);
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

    private static BigInteger requirePositive(
            BigInteger value,
            String fieldName
    ) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static BigInteger requireNonNegative(
            BigInteger value,
            String fieldName
    ) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    fieldName + " must be non-negative"
            );
        }
        return value;
    }

    private BigInteger requireResponseValue(
            BigInteger value,
            String fieldName
    ) {
        if (value == null) {
            throw nonRetryable("Ethereum response is missing " + fieldName);
        }
        return value;
    }

    private BigInteger requireNonNegativeResponse(
            BigInteger value,
            String fieldName
    ) {
        requireResponseValue(value, fieldName);
        if (value.signum() < 0) {
            throw nonRetryable("Ethereum response contains invalid " + fieldName);
        }
        return value;
    }

    BlockchainGatewayException retryable(String message) {
        return new BlockchainGatewayException(message, true);
    }

    BlockchainGatewayException nonRetryable(String message) {
        return new BlockchainGatewayException(message, false);
    }

}
