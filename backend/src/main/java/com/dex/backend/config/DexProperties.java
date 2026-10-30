package com.dex.backend.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "dex")
@Validated
public record DexProperties(
        @Valid @NotNull Blockchain blockchain,
        @Valid @NotNull Indexer indexer,
        @Valid @NotNull Matcher matcher,
        @Valid @NotNull Executor executor,
        @Valid @NotNull Api api
) {

    public record Blockchain(
            @NotNull BlockchainMode mode,
            @NotBlank String deploymentId,
            URI rpcUrl,
            BigInteger expectedChainId,
            String dexAddress,
            BigInteger deploymentBlock
    ) {
    }

    public record Indexer(
            @DefaultValue("true")
            boolean enabled,

            @DefaultValue("2s")
            @NotNull Duration pollInterval,

            @DefaultValue("0")
            @PositiveOrZero int confirmationDepth,

            @DefaultValue("500")
            @Min(1) @Max(10_000) int blockBatchSize
    ) {
    }

    public record Matcher(
            @DefaultValue("false")
            boolean enabled,

            @DefaultValue("30s")
            @NotNull Duration ineligibilityDuration
    ) {
    }

    public record Executor(
            String privateKey
    ) {
        @Override
        public String toString() {
            return "Executor[privateKey=<redacted>]";
        }
    }

    public record Api(
            @DefaultValue("http://localhost:5173")
            @NotEmpty List<URI> corsOrigins
    ) {
    }
}
