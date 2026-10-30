package com.dex.backend.persistence.deployment;

import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "chain_deployments")
public class ChainDeploymentEntity {

    @Id
    @Column(name = "deployment_id", length = 128, nullable = false)
    private String deploymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "adapter_mode", length = 16, nullable = false)
    private BlockchainMode adapterMode;

    @Column(name = "chain_id", precision = 78, scale = 0, nullable = false)
    private BigInteger chainId;

    @Column(name = "dex_address", length = 42, nullable = false)
    private EthereumAddress dexAddress;

    @Column(name = "market_registry_address", length = 42, nullable = false)
    private EthereumAddress marketRegistryAddress;

    @Column(name = "deployment_block_number", precision = 78, scale = 0, nullable = false)
    private BigInteger deploymentBlockNumber;

    @Column(name = "deployment_block_hash", length = 66, nullable = false)
    private Hash256 deploymentBlockHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChainDeploymentEntity() {
    }

    public ChainDeploymentEntity(
            String deploymentId,
            BlockchainMode adapterMode,
            BigInteger chainId,
            EthereumAddress dexAddress,
            EthereumAddress marketRegistryAddress,
            BigInteger deploymentBlockNumber,
            Hash256 deploymentBlockHash
    ) {
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId must not be null");
        this.adapterMode = Objects.requireNonNull(adapterMode, "adapterMode must not be null");
        this.chainId = Objects.requireNonNull(chainId, "chainId must not be null");
        this.dexAddress = Objects.requireNonNull(dexAddress, "dexAddress must not be null");
        this.marketRegistryAddress = Objects.requireNonNull(
                marketRegistryAddress,
                "marketRegistryAddress must not be null"
        );
        this.deploymentBlockNumber = Objects.requireNonNull(
                deploymentBlockNumber,
                "deploymentBlockNumber must not be null"
        );
        this.deploymentBlockHash = Objects.requireNonNull(
                deploymentBlockHash,
                "deploymentBlockHash must not be null"
        );
    }

    @PrePersist
    void setCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public BlockchainMode getAdapterMode() {
        return adapterMode;
    }

    public BigInteger getChainId() {
        return chainId;
    }

    public EthereumAddress getDexAddress() {
        return dexAddress;
    }

    public EthereumAddress getMarketRegistryAddress() {
        return marketRegistryAddress;
    }

    public BigInteger getDeploymentBlockNumber() {
        return deploymentBlockNumber;
    }

    public Hash256 getDeploymentBlockHash() {
        return deploymentBlockHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
