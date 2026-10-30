package com.dex.backend.blockchain.ethereum;

import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.SettlementRequest;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.protocol.core.methods.request.Transaction;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EthereumBlockchainGatewayTests {

    private static final String DEPLOYMENT_ID = "gateway-test";
    private static final BigInteger CHAIN_ID = BigInteger.valueOf(31337);
    private static final EthereumAddress ORDER_BOOK =
            new EthereumAddress("0x" + "d".repeat(40));
    private static final String PRIVATE_KEY = "0x" + "0".repeat(63) + "1";
    private static final EthereumAddress EXECUTOR = new EthereumAddress(
            "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"
    );
    private static final SettlementRequest REQUEST = new SettlementRequest(
            DEPLOYMENT_ID,
            BigInteger.ONE,
            BigInteger.TEN,
            BigInteger.valueOf(11),
            BigInteger.valueOf(40),
            BigInteger.valueOf(2_500_000)
    );

    private final EthereumRpcClient rpc = mock(EthereumRpcClient.class);
    private final EthereumDexClient dex = mock(EthereumDexClient.class);
    private final EthereumSettlementSigner signer =
            new EthereumSettlementSigner(CHAIN_ID, ORDER_BOOK, PRIVATE_KEY);
    private final EthereumBlockchainGateway gateway =
            new EthereumBlockchainGateway(
                    rpc,
                    dex,
                    DEPLOYMENT_ID,
                    ORDER_BOOK,
                    signer
            );

    @Test
    void preparesOneDurablePayloadFromNonceGasAndExactRequest() {
        when(rpc.getPendingExecutorNonce()).thenReturn(BigInteger.valueOf(9));
        when(rpc.getGasPrice()).thenReturn(BigInteger.valueOf(100));
        when(rpc.estimateGas(org.mockito.ArgumentMatchers.any()))
                .thenReturn(BigInteger.valueOf(100_000));

        PreparedSettlement prepared = gateway.prepareSettlement(REQUEST);

        assertThat(prepared.request()).isEqualTo(REQUEST);
        assertThat(prepared.executorAddress()).isEqualTo(EXECUTOR);
        assertThat(prepared.nonce()).isEqualTo(BigInteger.valueOf(9));
        assertThat(TransactionDecoder.decode(
                prepared.signedTransactionHex()
        ).getGasLimit()).isEqualTo(BigInteger.valueOf(120_000));

        ArgumentCaptor<Transaction> estimate = ArgumentCaptor.forClass(
                Transaction.class
        );
        verify(rpc).estimateGas(estimate.capture());
        assertThat(estimate.getValue().getFrom()).isEqualTo(EXECUTOR.value());
        assertThat(estimate.getValue().getTo()).isEqualTo(ORDER_BOOK.value());
        assertThat(estimate.getValue().getNonce()).isEqualTo("0x9");
        assertThat(estimate.getValue().getGasPrice()).isEqualTo("0x64");
        assertThat(estimate.getValue().getData())
                .isEqualTo(signer.encodeSettlement(REQUEST));
    }

    @Test
    void broadcastsOnlyThePersistedPayloadAndHash() {
        when(rpc.getPendingExecutorNonce()).thenReturn(BigInteger.ZERO);
        when(rpc.getGasPrice()).thenReturn(BigInteger.ONE);
        when(rpc.estimateGas(org.mockito.ArgumentMatchers.any()))
                .thenReturn(BigInteger.valueOf(50_000));
        PreparedSettlement prepared = gateway.prepareSettlement(REQUEST);
        when(rpc.broadcastRawTransaction(
                prepared.signedTransactionHex(),
                prepared.transactionHash()
        )).thenReturn(prepared.transactionHash());

        assertThat(gateway.broadcastSettlement(prepared))
                .isEqualTo(prepared.transactionHash());

        verify(rpc).broadcastRawTransaction(
                prepared.signedTransactionHex(),
                prepared.transactionHash()
        );
    }

    @Test
    void rejectsAnotherDeploymentBeforeAnySigningRpc() {
        SettlementRequest other = new SettlementRequest(
                "other-deployment",
                REQUEST.marketId(),
                REQUEST.buyOrderId(),
                REQUEST.sellOrderId(),
                REQUEST.fillLots(),
                REQUEST.executionPriceQuoteAtomsPerLot()
        );

        assertThatThrownBy(() -> gateway.prepareSettlement(other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Settlement request belongs to another deployment");

        verify(rpc, never()).getPendingExecutorNonce();
        verify(rpc, never()).getGasPrice();
    }

    @Test
    void readOnlyGatewayWithoutCredentialsCannotSignOrExposeExecutor() {
        EthereumBlockchainGateway readOnly = new EthereumBlockchainGateway(
                rpc,
                dex,
                DEPLOYMENT_ID,
                ORDER_BOOK,
                null
        );

        assertThatThrownBy(readOnly::getExecutorAddress)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Executor credentials are not configured");
        assertThatThrownBy(() -> readOnly.prepareSettlement(REQUEST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Executor credentials are not configured");
    }

    @Test
    void preparedExecutorMustMatchConfiguredSigner() {
        PreparedSettlement foreign = new PreparedSettlement(
                REQUEST,
                new EthereumAddress("0x" + "e".repeat(40)),
                BigInteger.ZERO,
                new Hash256("0x" + "a".repeat(64)),
                "0x0102"
        );

        assertThatThrownBy(() -> gateway.broadcastSettlement(foreign))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Prepared settlement executor does not match configuration"
                );
        verify(rpc, never()).broadcastRawTransaction(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
