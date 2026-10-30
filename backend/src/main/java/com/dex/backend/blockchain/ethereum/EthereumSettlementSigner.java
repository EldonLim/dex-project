package com.dex.backend.blockchain.ethereum;

import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.SettlementRequest;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import org.web3j.abi.FunctionEncoder;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Objects;

/** Signs one exact EIP-155 OrderBook settlement transaction. */
final class EthereumSettlementSigner {

    private final long chainId;
    private final EthereumAddress orderBookAddress;
    private final Credentials credentials;
    private final EthereumAddress executorAddress;

    EthereumSettlementSigner(
            BigInteger chainId,
            EthereumAddress orderBookAddress,
            String privateKey
    ) {
        Objects.requireNonNull(chainId, "chainId must not be null");
        if (chainId.signum() <= 0) {
            throw new IllegalArgumentException("chainId must be positive");
        }
        try {
            this.chainId = chainId.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "chainId is too large for EIP-155 signing"
            );
        }
        this.orderBookAddress = Objects.requireNonNull(
                orderBookAddress,
                "orderBookAddress must not be null"
        );
        Objects.requireNonNull(privateKey, "privateKey must not be null");
        try {
            this.credentials = Credentials.create(privateKey);
            this.executorAddress = new EthereumAddress(
                    credentials.getAddress()
            );
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Executor private key is invalid");
        }
    }

    EthereumAddress executorAddress() {
        return executorAddress;
    }

    String encodeSettlement(SettlementRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return FunctionEncoder.encode(EthereumDexAbi.executeTrade(
                request.buyOrderId(),
                request.sellOrderId(),
                request.fillLots(),
                request.executionPriceQuoteAtomsPerLot()
        ));
    }

    PreparedSettlement sign(
            SettlementRequest request,
            BigInteger nonce,
            BigInteger gasPrice,
            BigInteger gasLimit
    ) {
        Objects.requireNonNull(request, "request must not be null");
        requireNonNegative(nonce, "nonce");
        requireNonNegative(gasPrice, "gasPrice");
        requirePositive(gasLimit, "gasLimit");

        RawTransaction transaction = RawTransaction.createTransaction(
                nonce,
                gasPrice,
                gasLimit,
                orderBookAddress.value(),
                BigInteger.ZERO,
                encodeSettlement(request)
        );

        try {
            String payload = Numeric.toHexString(
                    TransactionEncoder.signMessage(
                            transaction,
                            chainId,
                            credentials
                    )
            ).toLowerCase(Locale.ROOT);
            return new PreparedSettlement(
                    request,
                    executorAddress,
                    nonce,
                    new Hash256(Hash.sha3(payload)),
                    payload
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Ethereum settlement signing failed"
            );
        }
    }

    @Override
    public String toString() {
        return "EthereumSettlementSigner[chainId=" + chainId
                + ", orderBookAddress=" + orderBookAddress
                + ", executorAddress=" + executorAddress
                + ", credentials=<redacted>]";
    }

    private static void requireNonNegative(
            BigInteger value,
            String fieldName
    ) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    fieldName + " must be non-negative"
            );
        }
    }

    private static void requirePositive(
            BigInteger value,
            String fieldName
    ) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
