package com.dex.backend.api.status;

import com.dex.backend.config.DexProperties;
import com.dex.backend.indexer.IndexerPollingWorker;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.Optional;

@Service
public class StatusQueryService {

    private final ChainDeploymentRepository deploymentRepository;
    private final IndexerCheckpointRepository checkpointRepository;
    private final DexProperties properties;
    private final Optional<IndexerPollingWorker> pollingWorker;

    public StatusQueryService(
            ChainDeploymentRepository deploymentRepository,
            IndexerCheckpointRepository checkpointRepository,
            DexProperties properties,
            Optional<IndexerPollingWorker> pollingWorker
    ) {
        this.deploymentRepository = deploymentRepository;
        this.checkpointRepository = checkpointRepository;
        this.properties = properties;
        this.pollingWorker = pollingWorker;
    }

    @Transactional(readOnly = true)
    public BackendStatusResponse getStatus() {
        String deploymentId = properties.blockchain().deploymentId();
        Optional<ChainDeploymentEntity> deployment = deploymentRepository.findById(deploymentId);
        Optional<IndexerCheckpointEntity> checkpoint = checkpointRepository.findById(deploymentId);

        boolean workerRunning = pollingWorker.map(IndexerPollingWorker::isRunning).orElse(false);
        boolean workerHalted = pollingWorker.map(IndexerPollingWorker::isHalted).orElse(false);
        boolean paused = checkpoint.map(IndexerCheckpointEntity::isPaused).orElse(false)
                || workerHalted;
        IndexerStatus indexerStatus = determineIndexerStatus(
                checkpoint,
                workerRunning,
                paused
        );
        MatcherStatus matcherStatus = properties.matcher().enabled()
                ? MatcherStatus.NOT_IMPLEMENTED
                : MatcherStatus.DISABLED;
        BackendOperationalStatus overallStatus = determineOverallStatus(
                deployment.isPresent(),
                checkpoint.isPresent(),
                indexerStatus,
                matcherStatus
        );

        return new BackendStatusResponse(
                overallStatus,
                properties.blockchain().mode(),
                toDeploymentResponse(deploymentId, deployment),
                toIndexerResponse(checkpoint, indexerStatus, paused),
                new MatcherStatusResponse(properties.matcher().enabled(), matcherStatus)
        );
    }

    private IndexerStatus determineIndexerStatus(
            Optional<IndexerCheckpointEntity> checkpoint,
            boolean workerRunning,
            boolean paused
    ) {
        if (!properties.indexer().enabled()) {
            return IndexerStatus.DISABLED;
        }
        if (paused) {
            return IndexerStatus.PAUSED;
        }
        if (workerRunning) {
            return IndexerStatus.RUNNING;
        }
        if (checkpoint.map(value -> value.getLastErrorAt() != null).orElse(false)) {
            return IndexerStatus.RETRYING;
        }
        if (checkpoint.isEmpty()) {
            return IndexerStatus.STARTING;
        }
        if (eligibleLag(checkpoint.orElseThrow()).signum() > 0) {
            return IndexerStatus.SYNCING;
        }
        return IndexerStatus.UP_TO_DATE;
    }

    private BackendOperationalStatus determineOverallStatus(
            boolean deploymentRegistered,
            boolean checkpointPresent,
            IndexerStatus indexerStatus,
            MatcherStatus matcherStatus
    ) {
        if (indexerStatus == IndexerStatus.DISABLED
                || indexerStatus == IndexerStatus.PAUSED
                || indexerStatus == IndexerStatus.RETRYING
                || matcherStatus == MatcherStatus.NOT_IMPLEMENTED) {
            return BackendOperationalStatus.DEGRADED;
        }
        if (!deploymentRegistered || !checkpointPresent) {
            return BackendOperationalStatus.STARTING;
        }
        return BackendOperationalStatus.HEALTHY;
    }

    private DeploymentStatusResponse toDeploymentResponse(
            String deploymentId,
            Optional<ChainDeploymentEntity> deployment
    ) {
        return deployment.map(value -> new DeploymentStatusResponse(
                deploymentId,
                true,
                value.getChainId().toString(),
                value.getDexAddress().value(),
                value.getDeploymentBlockNumber().toString(),
                value.getDeploymentBlockHash().value()
        )).orElseGet(() -> new DeploymentStatusResponse(
                deploymentId,
                false,
                null,
                null,
                null,
                null
        ));
    }

    private IndexerStatusResponse toIndexerResponse(
            Optional<IndexerCheckpointEntity> checkpoint,
            IndexerStatus indexerStatus,
            boolean paused
    ) {
        return checkpoint.map(value -> new IndexerStatusResponse(
                properties.indexer().enabled(),
                indexerStatus,
                value.getLatestObservedBlockNumber().toString(),
                value.getLastProcessedBlockNumber().toString(),
                eligibleLag(value).toString(),
                properties.indexer().confirmationDepth(),
                value.getLastSuccessfulSyncAt().toString(),
                value.getLastErrorAt() == null ? null : value.getLastErrorAt().toString(),
                paused
        )).orElseGet(() -> new IndexerStatusResponse(
                properties.indexer().enabled(),
                indexerStatus,
                null,
                null,
                null,
                properties.indexer().confirmationDepth(),
                null,
                null,
                paused
        ));
    }

    private BigInteger eligibleLag(IndexerCheckpointEntity checkpoint) {
        BigInteger lag = checkpoint.getLatestObservedBlockNumber()
                .subtract(BigInteger.valueOf(properties.indexer().confirmationDepth()))
                .subtract(checkpoint.getLastProcessedBlockNumber());
        return lag.max(BigInteger.ZERO);
    }
}
