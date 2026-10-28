package com.dex.backend.persistence.market;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.Objects;

@Embeddable
public class MarketEntityId implements Serializable {

    @Column(name = "deployment_id", length = 128, nullable = false)
    private String deploymentId;

    @Column(name = "market_id", precision = 78, scale = 0, nullable = false)
    private BigInteger marketId;

    protected MarketEntityId() {
    }

    public MarketEntityId(String deploymentId, BigInteger marketId) {
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId must not be null");
        this.marketId = Objects.requireNonNull(marketId, "marketId must not be null");
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public BigInteger getMarketId() {
        return marketId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MarketEntityId that)) {
            return false;
        }
        return deploymentId.equals(that.deploymentId) && marketId.equals(that.marketId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deploymentId, marketId);
    }
}
