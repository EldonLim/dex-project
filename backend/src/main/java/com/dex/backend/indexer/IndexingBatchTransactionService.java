package com.dex.backend.indexer;

import com.dex.backend.blockchain.event.DexEvent;
import com.dex.backend.blockchain.model.BlockRange;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.indexer.projection.EventProjectionResult;
import com.dex.backend.indexer.projection.EventProjectionService;
import com.dex.backend.persistence.deployment.ChainDeploymentEntity;
import com.dex.backend.persistence.deployment.ChainDeploymentRepository;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class IndexingBatchTransactionService {

    private final ChainDeploymentRepository deploymentRepository;
    private final IndexerCheckpointRepository checkpointRepository;
    private final EventProjectionService projectionService;

    public IndexingBatchTransactionService(
            ChainDeploymentRepository deploymentRepository,
            IndexerCheckpointRepository checkpointRepository,
            EventProjectionService projectionService
    ) {
        this.deploymentRepository = deploymentRepository;
        this.checkpointRepository = checkpointRepository;
        this.projectionService = projectionService;
    }

    @Transactional
    public BatchEventCounts applyBatch(
            String deploymentId,
            BlockRange range,
            BlockReference processedBlock,
            BlockReference latestObservedBlock,
            List<DexEvent> events
    ) {
        Objects.requireNonNull(deploymentId, "deploymentId must not be null");
        Objects.requireNonNull(range, "range must not be null");
        Objects.requireNonNull(processedBlock, "processedBlock must not be null");
        Objects.requireNonNull(latestObservedBlock, "latestObservedBlock must not be null");
        Objects.requireNonNull(events, "events must not be null");

        ChainDeploymentEntity deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new IndexerSafetyException(
                        "Cannot apply an indexing batch for an unknown deployment " + deploymentId
                ));
        Optional<IndexerCheckpointEntity> existingCheckpoint = checkpointRepository.findById(deploymentId);

        verifyBatchContinuity(deployment, existingCheckpoint, range, processedBlock, latestObservedBlock);

        if (events.stream().anyMatch(Objects::isNull)) {
            throw new IndexerSafetyException("Blockchain gateway returned a null event");
        }

        List<DexEvent> orderedEvents = new ArrayList<>(events);
        orderedEvents.sort(null);
        verifyEvents(deploymentId, range, orderedEvents);

        int appliedEvents = 0;
        int replayedEvents = 0;

        for (DexEvent event : orderedEvents) {
            EventProjectionResult result = projectionService.apply(event);

            if (result == EventProjectionResult.APPLIED) {
                appliedEvents++;
            } else {
                replayedEvents++;
            }
        }

        Instant successfulSyncAt = Instant.now();

        if (existingCheckpoint.isPresent()) {
            existingCheckpoint.orElseThrow().advance(
                    processedBlock,
                    latestObservedBlock,
                    successfulSyncAt
            );
        } else {
            checkpointRepository.save(new IndexerCheckpointEntity(
                    deploymentId,
                    processedBlock.number(),
                    processedBlock.hash(),
                    latestObservedBlock.number(),
                    latestObservedBlock.hash(),
                    successfulSyncAt,
                    null,
                    null,
                    false,
                    null
            ));
        }

        return new BatchEventCounts(appliedEvents, replayedEvents);
    }

    @Transactional
    public void recordSuccessfulObservation(
            String deploymentId,
            BlockReference latestObservedBlock
    ) {
        IndexerCheckpointEntity checkpoint = checkpointRepository.findById(deploymentId)
                .orElseThrow(() -> new IndexerSafetyException(
                        "Cannot record an observation without an indexer checkpoint"
                ));

        checkpoint.recordSuccessfulObservation(latestObservedBlock, Instant.now());
    }

    private void verifyBatchContinuity(
            ChainDeploymentEntity deployment,
            Optional<IndexerCheckpointEntity> checkpoint,
            BlockRange range,
            BlockReference processedBlock,
            BlockReference latestObservedBlock
    ) {
        BigInteger expectedStart;

        if (checkpoint.isPresent()) {
            IndexerCheckpointEntity current = checkpoint.orElseThrow();

            if (current.isPaused()) {
                throw new IndexerSafetyException(
                        "Cannot apply a batch while indexing is paused for "
                                + deployment.getDeploymentId()
                );
            }

            expectedStart = current.getLastProcessedBlockNumber().add(BigInteger.ONE);
        } else {
            expectedStart = deployment.getDeploymentBlockNumber();
        }

        if (!range.fromInclusive().equals(expectedStart)) {
            throw new IndexerSafetyException(
                    "Indexing batch is not continuous with the stored checkpoint"
            );
        }

        if (!processedBlock.number().equals(range.toInclusive())) {
            throw new IndexerSafetyException(
                    "Processed block does not match the end of the indexing range"
            );
        }

        if (latestObservedBlock.number().compareTo(processedBlock.number()) < 0) {
            throw new IndexerSafetyException(
                    "Latest observed block precedes the processed block"
            );
        }
    }

    private void verifyEvents(
            String deploymentId,
            BlockRange range,
            List<DexEvent> orderedEvents
    ) {
        DexEvent previous = null;

        for (DexEvent event : orderedEvents) {
            BigInteger blockNumber = event.metadata().position().blockNumber();

            if (!deploymentId.equals(event.metadata().deploymentId())) {
                throw new IndexerSafetyException(
                        "Blockchain gateway returned an event for a different deployment"
                );
            }

            if (blockNumber.compareTo(range.fromInclusive()) < 0
                    || blockNumber.compareTo(range.toInclusive()) > 0) {
                throw new IndexerSafetyException(
                        "Blockchain gateway returned an event outside the requested block range"
                );
            }

            if (previous != null && previous.compareTo(event) == 0) {
                throw new IndexerSafetyException(
                        "Blockchain gateway returned two events at the same chain position"
                );
            }

            previous = event;
        }
    }
}
