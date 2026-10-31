package com.dex.backend.indexer;

import com.dex.backend.blockchain.model.ChainIdentity;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class DeploymentIdentityService {

    private final ChainDeploymentRepository deploymentRepository;

    public DeploymentIdentityService(ChainDeploymentRepository deploymentRepository) {
        this.deploymentRepository = deploymentRepository;
    }

    @Transactional
    public void ensureRegistered(ChainIdentity identity, BlockchainMode mode) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(mode, "mode must not be null");

        deploymentRepository.findById(identity.deploymentId())
                .ifPresentOrElse(
                        existing -> verifyIdentity(existing, identity, mode),
                        () -> deploymentRepository.save(new ChainDeploymentEntity(
                                identity.deploymentId(),
                                mode,
                                identity.chainId(),
                                identity.dexAddress(),
                                identity.marketRegistryAddress(),
                                identity.deploymentBlockNumber(),
                                identity.deploymentBlockHash()
                        ))
                );
    }

    private void verifyIdentity(
            ChainDeploymentEntity existing,
            ChainIdentity observed,
            BlockchainMode mode
    ) {
        boolean matches = existing.getAdapterMode() == mode
                && existing.getChainId().equals(observed.chainId())
                && existing.getDexAddress().equals(observed.dexAddress())
                && existing.getMarketRegistryAddress().equals(
                observed.marketRegistryAddress()
        )
                && existing.getDeploymentBlockNumber().equals(observed.deploymentBlockNumber())
                && existing.getDeploymentBlockHash().equals(observed.deploymentBlockHash());

        if (!matches) {
            throw new IndexerSafetyException(
                    "Configured chain identity does not match stored deployment "
                            + observed.deploymentId()
            );
        }
    }
}
