package com.dex.backend.blockchain.ethereum;

import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.blockchain.execution.TransactionObservation;
import com.dex.backend.blockchain.execution.TransactionStatus;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.blockchain.model.ChainIdentity;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.EthTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EthereumRpcClientTests {

    private static final String DEPLOYMENT_ID = "ethereum-rpc-test";
    private static final BigInteger CHAIN_ID = BigInteger.valueOf(31337);
    private static final BigInteger DEPLOYMENT_BLOCK = BigInteger.valueOf(100);
    private static final EthereumAddress DEX = address("d");
    private static final EthereumAddress TOKEN = address("a");
    private static final EthereumAddress OWNER = address("1");
    private static final EthereumAddress SPENDER = address("2");
    private static final EthereumAddress EXECUTOR = address("e");
    private static final Hash256 BLOCK_HASH = hash("a");
    private static final Hash256 TRANSACTION_HASH = hash("b");

    private final Web3j web3j = mock(Web3j.class);

    @Test
    void chainIdentityRequiresConfiguredChainAndDeploymentBlock()
            throws IOException {
        doReturn(request(chainId(CHAIN_ID))).when(web3j).ethChainId();
        doReturn(request(blockResponse(
                DEPLOYMENT_BLOCK,
                BLOCK_HASH,
                BigInteger.valueOf(1_735_689_600L)
        ))).when(web3j).ethGetBlockByNumber(
                any(DefaultBlockParameter.class),
                anyBoolean()
        );

        ChainIdentity identity = client().getChainIdentity();

        assertThat(identity).isEqualTo(new ChainIdentity(
                DEPLOYMENT_ID,
                CHAIN_ID,
                DEX,
                DEPLOYMENT_BLOCK,
                BLOCK_HASH
        ));
    }

    @Test
    void chainMismatchIsNonRetryableAndSkipsBlockLookup() throws IOException {
        doReturn(request(chainId(BigInteger.ONE))).when(web3j).ethChainId();

        assertThatThrownBy(() -> client().getChainIdentity())
                .isInstanceOfSatisfying(
                        BlockchainGatewayException.class,
                        exception -> assertThat(exception.isRetryable()).isFalse()
                )
                .hasMessage("Ethereum chain ID does not match configuration");

        verify(web3j, never()).ethGetBlockByNumber(any(), eq(false));
    }

    @Test
    void missingChainIdIsRejectedAsMalformed() throws IOException {
        doReturn(request(new EthChainId())).when(web3j).ethChainId();

        assertThatThrownBy(() -> client().getChainIdentity())
                .isInstanceOfSatisfying(
                        BlockchainGatewayException.class,
                        exception -> assertThat(exception.isRetryable()).isFalse()
                )
                .hasMessage("Ethereum response contains invalid chain ID");
    }

    @Test
    void blockReadsPreserveLargeNumbersHashesAndTimestamps()
            throws IOException {
        BigInteger largeNumber = BigInteger.ONE.shiftLeft(80);
        BigInteger timestamp = BigInteger.valueOf(1_735_689_612L);
        doReturn(
                request(blockResponse(largeNumber, BLOCK_HASH, timestamp)),
                request(blockResponse(largeNumber, BLOCK_HASH, timestamp))
        ).when(web3j).ethGetBlockByNumber(
                any(DefaultBlockParameter.class),
                anyBoolean()
        );

        BlockReference expected = new BlockReference(
                largeNumber,
                BLOCK_HASH,
                Instant.ofEpochSecond(timestamp.longValueExact())
        );

        assertThat(client().getBlock(largeNumber)).contains(expected);
        assertThat(client().getLatestBlock()).isEqualTo(expected);
    }

    @Test
    void absentNumberedBlockIsEmptyButAbsentLatestBlockIsInvalid()
            throws IOException {
        doReturn(request(new EthBlock()), request(new EthBlock()))
                .when(web3j)
                .ethGetBlockByNumber(
                        any(DefaultBlockParameter.class),
                        anyBoolean()
                );

        assertThat(client().getBlock(BigInteger.ONE)).isEmpty();
        assertThatThrownBy(() -> client().getLatestBlock())
                .isInstanceOf(BlockchainGatewayException.class)
                .hasMessage("Latest Ethereum block response is missing");
    }

    @Test
    void erc20ReadsEncodeStandardCallsAndPreserveUint256Values()
            throws IOException {
        BigInteger balance = BigInteger.ONE.shiftLeft(200).add(BigInteger.TEN);
        BigInteger allowance = BigInteger.ONE.shiftLeft(220).add(BigInteger.ONE);
        EthCall balanceResponse = uint256Response(balance);
        EthCall allowanceResponse = uint256Response(allowance);
        doReturn(request(balanceResponse), request(allowanceResponse))
                .when(web3j)
                .ethCall(any(), eq(DefaultBlockParameterName.LATEST));

        assertThat(client().getTokenBalance(TOKEN, OWNER)).isEqualTo(balance);
        assertThat(client().getTokenAllowance(TOKEN, OWNER, SPENDER))
                .isEqualTo(allowance);

        ArgumentCaptor<Transaction> call = ArgumentCaptor.forClass(
                Transaction.class
        );
        verify(web3j, org.mockito.Mockito.times(2)).ethCall(
                call.capture(),
                eq(DefaultBlockParameterName.LATEST)
        );
        assertThat(call.getAllValues().get(0).getTo()).isEqualTo(TOKEN.value());
        assertThat(call.getAllValues().get(0).getData())
                .startsWith("0x70a08231")
                .contains(OWNER.value().substring(2));
        assertThat(call.getAllValues().get(1).getData())
                .startsWith("0xdd62ed3e")
                .contains(OWNER.value().substring(2))
                .contains(SPENDER.value().substring(2));
    }

    @Test
    void malformedErc20ResultIsNonRetryable() throws IOException {
        EthCall response = new EthCall();
        response.setResult("0x1234");
        doReturn(request(response)).when(web3j).ethCall(
                any(),
                eq(DefaultBlockParameterName.LATEST)
        );

        assertThatThrownBy(() -> client().getTokenBalance(TOKEN, OWNER))
                .isInstanceOfSatisfying(
                        BlockchainGatewayException.class,
                        exception -> assertThat(exception.isRetryable()).isFalse()
                )
                .hasMessage("Ethereum contract call returned malformed data");
    }

    @Test
    void configuredPrivateKeyIsReducedToExecutorAddressAndPendingNonce()
            throws IOException {
        String privateKey = "0x" + "0".repeat(63) + "1";
        DexProperties properties = ethereumProperties(privateKey);
        EthGetTransactionCount response = new EthGetTransactionCount();
        response.setResult("0x10000000000000000");
        doReturn(request(response)).when(web3j).ethGetTransactionCount(
                "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf",
                DefaultBlockParameterName.PENDING
        );

        EthereumRpcClient client = EthereumRpcClient.fromProperties(
                web3j,
                properties
        );

        assertThat(client.getExecutorAddress()).contains(new EthereumAddress(
                "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"
        ));
        assertThat(client.getPendingExecutorNonce())
                .isEqualTo(new BigInteger("10000000000000000", 16));
        assertThat(client.toString()).doesNotContain(privateKey);
    }

    @Test
    void nonceReadRequiresConfiguredExecutor() {
        EthereumRpcClient client = new EthereumRpcClient(
                web3j,
                DEPLOYMENT_ID,
                CHAIN_ID,
                DEX,
                DEPLOYMENT_BLOCK,
                null
        );

        assertThat(client.getExecutorAddress()).isEmpty();
        assertThatThrownBy(client::getPendingExecutorNonce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Executor credentials are not configured");
    }

    @Test
    void broadcastChecksLocalAndReturnedTransactionIdentity()
            throws IOException {
        String payload = "0x0102";
        Hash256 expectedHash = new Hash256(Hash.sha3(payload));
        EthSendTransaction response = new EthSendTransaction();
        response.setResult(expectedHash.value().toUpperCase());
        doReturn(request(response)).when(web3j).ethSendRawTransaction(payload);

        assertThat(client().broadcastRawTransaction(payload, expectedHash))
                .isEqualTo(expectedHash);

        EthSendTransaction mismatch = new EthSendTransaction();
        mismatch.setResult(TRANSACTION_HASH.value());
        doReturn(request(mismatch)).when(web3j).ethSendRawTransaction(payload);

        assertThatThrownBy(() -> client().broadcastRawTransaction(
                payload,
                expectedHash
        )).isInstanceOfSatisfying(
                BlockchainGatewayException.class,
                exception -> assertThat(exception.isRetryable()).isFalse()
        ).hasMessage("Ethereum node returned an unexpected transaction hash");
    }

    @Test
    void transactionObservationMapsNotFoundAndPending() throws IOException {
        EthGetTransactionReceipt noReceipt = new EthGetTransactionReceipt();
        EthTransaction missing = new EthTransaction();
        EthTransaction pending = new EthTransaction();
        org.web3j.protocol.core.methods.response.Transaction transaction =
                new org.web3j.protocol.core.methods.response.Transaction();
        transaction.setHash(TRANSACTION_HASH.value());
        pending.setResult(transaction);

        doReturn(request(noReceipt), request(noReceipt))
                .when(web3j)
                .ethGetTransactionReceipt(TRANSACTION_HASH.value());
        doReturn(request(missing), request(pending))
                .when(web3j)
                .ethGetTransactionByHash(TRANSACTION_HASH.value());

        assertThat(client().getTransaction(TRANSACTION_HASH).status())
                .isEqualTo(TransactionStatus.NOT_FOUND);
        assertThat(client().getTransaction(TRANSACTION_HASH).status())
                .isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void transactionObservationMapsSuccessfulAndRevertedReceipts()
            throws IOException {
        TransactionReceipt success = receipt("0x1");
        TransactionReceipt revert = receipt("0x0");
        EthGetTransactionReceipt successResponse = receiptResponse(success);
        EthGetTransactionReceipt revertResponse = receiptResponse(revert);
        doReturn(request(successResponse), request(revertResponse))
                .when(web3j)
                .ethGetTransactionReceipt(TRANSACTION_HASH.value());

        TransactionObservation succeeded = client().getTransaction(
                TRANSACTION_HASH
        );
        TransactionObservation reverted = client().getTransaction(
                TRANSACTION_HASH
        );

        assertThat(succeeded.status()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(succeeded.blockNumber()).isEqualTo(BigInteger.valueOf(200));
        assertThat(succeeded.blockHash()).isEqualTo(BLOCK_HASH);
        assertThat(reverted.status()).isEqualTo(TransactionStatus.REVERTED);
        assertThat(reverted.failureReason())
                .isEqualTo("Ethereum transaction reverted");
    }

    @Test
    void transportAndServerFailuresAreRetryableButBadRequestsAreNot()
            throws IOException {
        @SuppressWarnings("rawtypes")
        Request transportRequest = mock(Request.class);
        when(transportRequest.send()).thenThrow(new IOException("secret URL"));
        doReturn(transportRequest).when(web3j).ethChainId();

        assertThatThrownBy(() -> client().getChainIdentity())
                .isInstanceOfSatisfying(
                        BlockchainGatewayException.class,
                        exception -> {
                            assertThat(exception.isRetryable()).isTrue();
                            assertThat(exception.getMessage())
                                    .doesNotContain("secret URL");
                        }
                );

        EthChainId serverError = rpcError(-32000, "sensitive node text");
        doReturn(request(serverError)).when(web3j).ethChainId();
        assertThatThrownBy(() -> client().getChainIdentity())
                .isInstanceOfSatisfying(
                        BlockchainGatewayException.class,
                        exception -> {
                            assertThat(exception.isRetryable()).isTrue();
                            assertThat(exception.getMessage())
                                    .doesNotContain("sensitive node text");
                        }
                );

        EthChainId invalidRequest = rpcError(-32602, "sensitive params");
        doReturn(request(invalidRequest)).when(web3j).ethChainId();
        assertThatThrownBy(() -> client().getChainIdentity())
                .isInstanceOfSatisfying(
                        BlockchainGatewayException.class,
                        exception -> {
                            assertThat(exception.isRetryable()).isFalse();
                            assertThat(exception.getMessage())
                                    .doesNotContain("sensitive params");
                        }
                );
    }

    private EthereumRpcClient client() {
        return new EthereumRpcClient(
                web3j,
                DEPLOYMENT_ID,
                CHAIN_ID,
                DEX,
                DEPLOYMENT_BLOCK,
                EXECUTOR
        );
    }

    private static DexProperties ethereumProperties(String privateKey) {
        return new DexProperties(
                new DexProperties.Blockchain(
                        BlockchainMode.ETHEREUM,
                        DEPLOYMENT_ID,
                        URI.create("http://localhost:8545"),
                        CHAIN_ID,
                        DEX.value(),
                        DEPLOYMENT_BLOCK
                ),
                new DexProperties.Indexer(false, Duration.ofSeconds(2), 0, 500),
                new DexProperties.Matcher(
                        false,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(30)
                ),
                new DexProperties.Executor(privateKey, Duration.ofSeconds(2)),
                new DexProperties.Api(List.of(URI.create("http://localhost:5173")))
        );
    }

    private static EthChainId chainId(BigInteger value) {
        EthChainId response = new EthChainId();
        response.setResult("0x" + value.toString(16));
        return response;
    }

    private static EthBlock blockResponse(
            BigInteger number,
            Hash256 hash,
            BigInteger timestamp
    ) {
        EthBlock.Block block = new EthBlock.Block();
        block.setNumber("0x" + number.toString(16));
        block.setHash(hash.value());
        block.setTimestamp("0x" + timestamp.toString(16));
        EthBlock response = new EthBlock();
        response.setResult(block);
        return response;
    }

    private static EthCall uint256Response(BigInteger value) {
        EthCall response = new EthCall();
        response.setResult("0x" + leftPad64(value.toString(16)));
        return response;
    }

    private static TransactionReceipt receipt(String status) {
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setTransactionHash(TRANSACTION_HASH.value());
        receipt.setBlockNumber("0xc8");
        receipt.setBlockHash(BLOCK_HASH.value());
        receipt.setStatus(status);
        return receipt;
    }

    private static EthGetTransactionReceipt receiptResponse(
            TransactionReceipt receipt
    ) {
        EthGetTransactionReceipt response = new EthGetTransactionReceipt();
        response.setResult(receipt);
        return response;
    }

    private static EthChainId rpcError(int code, String message) {
        EthChainId response = new EthChainId();
        response.setError(new Response.Error(code, message));
        return response;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Request request(Response<?> response)
            throws IOException {
        Request request = mock(Request.class);
        when(request.send()).thenReturn(response);
        return request;
    }

    private static EthereumAddress address(String digit) {
        return new EthereumAddress("0x" + digit.repeat(40));
    }

    private static Hash256 hash(String digit) {
        return new Hash256("0x" + digit.repeat(64));
    }

    private static String leftPad64(String value) {
        return "0".repeat(64 - value.length()) + value;
    }
}
