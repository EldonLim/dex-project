package com.dex.backend.matcher;

import com.dex.backend.config.DexProperties;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.Optional;

@Service
public class MatcherReadinessService {

    private final ChainDeploymentRepository deploymentRepository;
    private final IndexerCheckpointRepository checkpointRepository;
    private final DexProperties properties;

    public MatcherReadinessService(
            ChainDeploymentRepository deploymentRepository,
            IndexerCheckpointRepository checkpointRepository,
            DexProperties properties
    ) {
        this.deploymentRepository = deploymentRepository;
        this.checkpointRepository = checkpointRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public boolean isReady() {
        if (!properties.indexer().enabled()) {
            return false;
        }

        String deploymentId = properties.blockchain().deploymentId();

        if (!deploymentRepository.existsById(deploymentId)) {
            return false;
        }

        Optional<IndexerCheckpointEntity> checkpoint =
                checkpointRepository.findById(deploymentId);

        return checkpoint.filter(value -> !value.isPaused())
                .filter(value -> value.getLastErrorAt() == null)
                .filter(this::hasNoEligibleLag)
                .isPresent();
    }

    private boolean hasNoEligibleLag(IndexerCheckpointEntity checkpoint) {
        BigInteger eligibleTip = checkpoint.getLatestObservedBlockNumber()
                .subtract(BigInteger.valueOf(
                        properties.indexer().confirmationDepth()
                ));

        return checkpoint.getLastProcessedBlockNumber()
                .compareTo(eligibleTip) >= 0;
    }
}
