package com.dex.backend.api.status;

import com.dex.backend.config.DexProperties;
import com.dex.backend.indexer.IndexerPollingWorker;
import com.dex.backend.matcher.MatcherPollingWorker;
import com.dex.backend.matcher.MatcherWorkerState;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import com.dex.backend.settlement.SettlementExecutorWorker;
import com.dex.backend.settlement.SettlementExecutorWorkerState;
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
    private final Optional<MatcherPollingWorker> matcherWorker;
    private final Optional<SettlementExecutorWorker> executorWorker;

    public StatusQueryService(
            ChainDeploymentRepository deploymentRepository,
            IndexerCheckpointRepository checkpointRepository,
            DexProperties properties,
            Optional<IndexerPollingWorker> pollingWorker,
            Optional<MatcherPollingWorker> matcherWorker,
            Optional<SettlementExecutorWorker> executorWorker
    ) {
        this.deploymentRepository = deploymentRepository;
        this.checkpointRepository = checkpointRepository;
        this.properties = properties;
        this.pollingWorker = pollingWorker;
        this.matcherWorker = matcherWorker;
        this.executorWorker = executorWorker;
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
        MatcherStatus matcherStatus = determineMatcherStatus();
        ExecutorStatus executorStatus = determineExecutorStatus();
        BackendOperationalStatus overallStatus = determineOverallStatus(
                deployment.isPresent(),
                checkpoint.isPresent(),
                indexerStatus,
                matcherStatus,
                executorStatus
        );

        return new BackendStatusResponse(
                overallStatus,
                properties.blockchain().mode(),
                toDeploymentResponse(deploymentId, deployment),
                toIndexerResponse(checkpoint, indexerStatus, paused),
                new MatcherStatusResponse(
                        properties.matcher().enabled(),
                        matcherStatus
                ),
                new ExecutorStatusResponse(
                        properties.matcher().enabled(),
                        executorStatus
                )
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
            MatcherStatus matcherStatus,
            ExecutorStatus executorStatus
    ) {
        if (indexerStatus == IndexerStatus.DISABLED
                || indexerStatus == IndexerStatus.PAUSED
                || indexerStatus == IndexerStatus.RETRYING
                || matcherStatus == MatcherStatus.RETRYING
                || matcherStatus == MatcherStatus.HALTED
                || executorStatus == ExecutorStatus.RETRYING
                || executorStatus == ExecutorStatus.HALTED) {
            return BackendOperationalStatus.DEGRADED;
        }
        if (!deploymentRegistered
                || !checkpointPresent
                || matcherStatus == MatcherStatus.STARTING
                || matcherStatus == MatcherStatus.WAITING_FOR_INDEXER
                || executorStatus == ExecutorStatus.STARTING) {
            return BackendOperationalStatus.STARTING;
        }
        return BackendOperationalStatus.HEALTHY;
    }

    private MatcherStatus determineMatcherStatus() {
        if (!properties.matcher().enabled()) {
            return MatcherStatus.DISABLED;
        }

        return matcherWorker.map(worker -> {
            if (worker.isHalted()) {
                return MatcherStatus.HALTED;
            }
            if (worker.isRunning()) {
                return MatcherStatus.RUNNING;
            }
            return toMatcherStatus(worker.getState());
        }).orElse(MatcherStatus.STARTING);
    }

    private MatcherStatus toMatcherStatus(MatcherWorkerState state) {
        return switch (state) {
            case STARTING -> MatcherStatus.STARTING;
            case WAITING_FOR_INDEXER -> MatcherStatus.WAITING_FOR_INDEXER;
            case IDLE -> MatcherStatus.IDLE;
            case RUNNING -> MatcherStatus.RUNNING;
            case RESERVED -> MatcherStatus.RESERVED;
            case RETRYING -> MatcherStatus.RETRYING;
            case HALTED -> MatcherStatus.HALTED;
        };
    }

    private ExecutorStatus determineExecutorStatus() {
        if (!properties.matcher().enabled()) {
            return ExecutorStatus.DISABLED;
        }

        return executorWorker.map(worker -> {
            if (worker.isHalted()) {
                return ExecutorStatus.HALTED;
            }
            if (worker.isRunning()) {
                return ExecutorStatus.RUNNING;
            }
            return toExecutorStatus(worker.getState());
        }).orElse(ExecutorStatus.STARTING);
    }

    private ExecutorStatus toExecutorStatus(
            SettlementExecutorWorkerState state
    ) {
        return switch (state) {
            case STARTING -> ExecutorStatus.STARTING;
            case IDLE -> ExecutorStatus.IDLE;
            case RUNNING -> ExecutorStatus.RUNNING;
            case SIGNED -> ExecutorStatus.SIGNED;
            case WAITING_FOR_RECONCILIATION ->
                    ExecutorStatus.WAITING_FOR_RECONCILIATION;
            case RETRYING -> ExecutorStatus.RETRYING;
            case HALTED -> ExecutorStatus.HALTED;
        };
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
