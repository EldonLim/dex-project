package com.dex.backend.blockchain.ethereum;

import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.SettlementRequest;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import org.junit.jupiter.api.Test;
import org.web3j.abi.FunctionEncoder;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EthereumSettlementSignerTests {

    private static final BigInteger CHAIN_ID = BigInteger.valueOf(31337);
    private static final EthereumAddress ORDER_BOOK =
            new EthereumAddress("0x" + "d".repeat(40));
    private static final String PRIVATE_KEY = "0x" + "0".repeat(63) + "1";
    private static final EthereumAddress EXECUTOR = new EthereumAddress(
            "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"
    );
    private static final SettlementRequest REQUEST = new SettlementRequest(
            "signing-test",
            BigInteger.ONE,
            BigInteger.TEN,
            BigInteger.valueOf(11),
            BigInteger.valueOf(40),
            BigInteger.valueOf(2_500_000)
    );

    @Test
    void signsExactExecuteTradeCallWithEip155Identity() throws Exception {
        EthereumSettlementSigner signer = signer();

        PreparedSettlement prepared = signer.sign(
                REQUEST,
                BigInteger.valueOf(7),
                BigInteger.valueOf(2_000_000_000L),
                BigInteger.valueOf(150_000)
        );

        RawTransaction decoded = TransactionDecoder.decode(
                prepared.signedTransactionHex()
        );
        SignedRawTransaction signed = (SignedRawTransaction) decoded;
        String expectedData = FunctionEncoder.encode(
                EthereumDexAbi.executeTrade(
                        REQUEST.buyOrderId(),
                        REQUEST.sellOrderId(),
                        REQUEST.fillLots(),
                        REQUEST.executionPriceQuoteAtomsPerLot()
                )
        );

        assertThat(prepared.request()).isEqualTo(REQUEST);
        assertThat(prepared.executorAddress()).isEqualTo(EXECUTOR);
        assertThat(prepared.nonce()).isEqualTo(BigInteger.valueOf(7));
        assertThat(prepared.transactionHash()).isEqualTo(
                new Hash256(Hash.sha3(prepared.signedTransactionHex()))
        );
        assertThat(signed.getFrom()).isEqualTo(EXECUTOR.value());
        assertThat(signed.getChainId()).isEqualTo(CHAIN_ID.longValueExact());
        assertThat(signed.getTo()).isEqualTo(ORDER_BOOK.value());
        assertThat(signed.getValue()).isEqualTo(BigInteger.ZERO);
        assertThat(signed.getNonce()).isEqualTo(BigInteger.valueOf(7));
        assertThat(signed.getGasPrice())
                .isEqualTo(BigInteger.valueOf(2_000_000_000L));
        assertThat(signed.getGasLimit()).isEqualTo(BigInteger.valueOf(150_000));
        assertThat("0x" + signed.getData()).isEqualTo(expectedData);
    }

    @Test
    void signingIsDeterministicAndSecretsAreRedacted() {
        EthereumSettlementSigner signer = signer();
        PreparedSettlement first = signer.sign(
                REQUEST,
                BigInteger.ZERO,
                BigInteger.ONE,
                BigInteger.valueOf(100_000)
        );
        PreparedSettlement second = signer.sign(
                REQUEST,
                BigInteger.ZERO,
                BigInteger.ONE,
                BigInteger.valueOf(100_000)
        );

        assertThat(second).isEqualTo(first);
        assertThat(signer.toString())
                .doesNotContain(PRIVATE_KEY)
                .doesNotContain(first.signedTransactionHex())
                .contains("credentials=<redacted>");
        assertThat(first.toString())
                .doesNotContain(first.signedTransactionHex());
    }

    @Test
    void rejectsChainIdsThatCannotBeSignedByWeb3jEip155() {
        assertThatThrownBy(() -> new EthereumSettlementSigner(
                BigInteger.ONE.shiftLeft(80),
                ORDER_BOOK,
                PRIVATE_KEY
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("chainId is too large for EIP-155 signing");
    }

    private EthereumSettlementSigner signer() {
        return new EthereumSettlementSigner(
                CHAIN_ID,
                ORDER_BOOK,
                PRIVATE_KEY
        );
    }
}
