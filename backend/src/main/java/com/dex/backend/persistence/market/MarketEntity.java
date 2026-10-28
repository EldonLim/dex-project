package com.dex.backend.persistence.market;

import com.dex.backend.blockchain.model.EthereumAddress;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "markets")
public class MarketEntity {

    @EmbeddedId
    private MarketEntityId id;

    @Column(name = "base_token_address", length = 42, nullable = false)
    private EthereumAddress baseTokenAddress;

    @Column(name = "quote_token_address", length = 42, nullable = false)
    private EthereumAddress quoteTokenAddress;

    @Column(name = "base_symbol", length = 32, nullable = false)
    private String baseSymbol;

    @Column(name = "quote_symbol", length = 32, nullable = false)
    private String quoteSymbol;

    @Column(name = "base_decimals", nullable = false)
    private short baseDecimals;

    @Column(name = "quote_decimals", nullable = false)
    private short quoteDecimals;

    @Column(name = "base_lot_size_atoms", precision = 78, scale = 0, nullable = false)
    private BigInteger baseLotSizeAtoms;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MarketEntity() {
    }

    public MarketEntity(
            MarketEntityId id,
            EthereumAddress baseTokenAddress,
            EthereumAddress quoteTokenAddress,
            String baseSymbol,
            String quoteSymbol,
            int baseDecimals,
            int quoteDecimals,
            BigInteger baseLotSizeAtoms,
            boolean active
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.baseTokenAddress = Objects.requireNonNull(baseTokenAddress, "baseTokenAddress must not be null");
        this.quoteTokenAddress = Objects.requireNonNull(quoteTokenAddress, "quoteTokenAddress must not be null");
        this.baseSymbol = Objects.requireNonNull(baseSymbol, "baseSymbol must not be null");
        this.quoteSymbol = Objects.requireNonNull(quoteSymbol, "quoteSymbol must not be null");
        this.baseDecimals = checkedDecimals(baseDecimals, "baseDecimals");
        this.quoteDecimals = checkedDecimals(quoteDecimals, "quoteDecimals");
        this.baseLotSizeAtoms = Objects.requireNonNull(baseLotSizeAtoms, "baseLotSizeAtoms must not be null");
        this.active = active;
    }

    private static short checkedDecimals(int decimals, String name) {
        if (decimals < 0 || decimals > 255) {
            throw new IllegalArgumentException(name + " must be between 0 and 255");
        }
        return (short) decimals;
    }

    @PrePersist
    void setCreationTimestamps() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void setUpdateTimestamp() {
        updatedAt = Instant.now();
    }

    public MarketEntityId getId() {
        return id;
    }

    public EthereumAddress getBaseTokenAddress() {
        return baseTokenAddress;
    }

    public EthereumAddress getQuoteTokenAddress() {
        return quoteTokenAddress;
    }

    public String getBaseSymbol() {
        return baseSymbol;
    }

    public String getQuoteSymbol() {
        return quoteSymbol;
    }

    public int getBaseDecimals() {
        return baseDecimals;
    }

    public int getQuoteDecimals() {
        return quoteDecimals;
    }

    public BigInteger getBaseLotSizeAtoms() {
        return baseLotSizeAtoms;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
