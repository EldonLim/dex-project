package com.dex.backend.api.status;

public record DeploymentStatusResponse(
        String deploymentId,
        boolean registered,
        String chainId,
        String dexAddress,
        String marketRegistryAddress,
        String deploymentBlockNumber,
        String deploymentBlockHash
) {
}
