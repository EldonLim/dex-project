package com.dex.backend.persistence.event;

import com.dex.backend.blockchain.model.Hash256;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.Objects;

@Embeddable
public class ProcessedEventEntityId implements Serializable {

    @Column(name = "deployment_id", length = 128, nullable = false)
    private String deploymentId;

    @Column(name = "transaction_hash", length = 66, nullable = false)
    private Hash256 transactionHash;

    @Column(name = "log_index", precision = 78, scale = 0, nullable = false)
    private BigInteger logIndex;

    protected ProcessedEventEntityId() {
    }

    public ProcessedEventEntityId(String deploymentId, Hash256 transactionHash, BigInteger logIndex) {
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId must not be null");
        this.transactionHash = Objects.requireNonNull(transactionHash, "transactionHash must not be null");
        this.logIndex = Objects.requireNonNull(logIndex, "logIndex must not be null");
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public Hash256 getTransactionHash() {
        return transactionHash;
    }

    public BigInteger getLogIndex() {
        return logIndex;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessedEventEntityId that)) {
            return false;
        }
        return deploymentId.equals(that.deploymentId)
                && transactionHash.equals(that.transactionHash)
                && logIndex.equals(that.logIndex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deploymentId, transactionHash, logIndex);
    }
}
