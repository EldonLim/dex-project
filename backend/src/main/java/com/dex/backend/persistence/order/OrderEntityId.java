package com.dex.backend.persistence.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.Objects;

@Embeddable
public class OrderEntityId implements Serializable {

    @Column(name = "deployment_id", length = 128, nullable = false)
    private String deploymentId;

    @Column(name = "order_id", precision = 78, scale = 0, nullable = false)
    private BigInteger orderId;

    protected OrderEntityId() {
    }

    public OrderEntityId(String deploymentId, BigInteger orderId) {
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId must not be null");
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public BigInteger getOrderId() {
        return orderId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OrderEntityId that)) {
            return false;
        }
        return deploymentId.equals(that.deploymentId) && orderId.equals(that.orderId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deploymentId, orderId);
    }
}
