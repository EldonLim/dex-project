package com.dex.backend.indexer;

import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.config.DexProperties;
import com.dex.backend.indexer.projection.EventProjectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        prefix = "dex.indexer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class IndexerPollingWorker {

    private static final Logger log = LoggerFactory.getLogger(IndexerPollingWorker.class);
    private static final int MAX_STORED_MESSAGE_LENGTH = 4_000;

    private final IndexingBatchCoordinator coordinator;
    private final IndexerOperationalStateService operationalStateService;
    private final String deploymentId;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean halted = new AtomicBoolean(false);

    public IndexerPollingWorker(
            IndexingBatchCoordinator coordinator,
            IndexerOperationalStateService operationalStateService,
            DexProperties properties
    ) {
        this.coordinator = coordinator;
        this.operationalStateService = operationalStateService;
        this.deploymentId = properties.blockchain().deploymentId();
    }

    @Scheduled(
            initialDelayString = "${dex.indexer.poll-interval:2s}",
            fixedDelayString = "${dex.indexer.poll-interval:2s}"
    )
    public void pollOnSchedule() {
        pollOnce();
    }

    public boolean pollOnce() {
        if (halted.get()) {
            log.debug("Skipping DEX indexing poll because the worker is halted");
            return false;
        }

        if (!running.compareAndSet(false, true)) {
            log.debug("Skipping overlapping DEX indexing poll");
            return false;
        }

        try {
            IndexingBatchResult result = coordinator.indexNextBatch();

            if (result.status() == IndexingBatchStatus.PAUSED) {
                halted.set(true);
                log.warn("DEX indexing is paused for deployment {}", deploymentId);
            }

            return true;
        } catch (IndexerSafetyException | EventProjectionException exception) {
            haltForSafety(exception);
            return true;
        } catch (BlockchainGatewayException exception) {
            if (exception.isRetryable()) {
                recordTransientFailure(exception);
            } else {
                haltForSafety(exception);
            }
            return true;
        } catch (RuntimeException exception) {
            recordTransientFailure(exception);
            return true;
        } finally {
            running.set(false);
        }
    }

    private void recordTransientFailure(RuntimeException failure) {
        String message = failureMessage(failure);
        boolean persisted = persistFailure(() ->
                operationalStateService.recordTransientFailure(deploymentId, message)
        );

        if (persisted) {
            log.warn(
                    "DEX indexing failed for deployment {}; the same range will be retried: {}",
                    deploymentId,
                    message
            );
        } else {
            log.warn(
                    "DEX indexing failed before a checkpoint existed for deployment {}: {}",
                    deploymentId,
                    message
            );
        }
    }

    private void haltForSafety(RuntimeException failure) {
        halted.set(true);

        String reason = failureMessage(failure);
        boolean persisted = persistFailure(() ->
                operationalStateService.pause(deploymentId, reason)
        );

        if (persisted) {
            log.error(
                    "DEX indexing paused for deployment {}: {}",
                    deploymentId,
                    reason
            );
        } else {
            log.error(
                    "DEX indexing halted before a checkpoint existed for deployment {}: {}",
                    deploymentId,
                    reason
            );
        }
    }

    private boolean persistFailure(FailurePersistence operation) {
        try {
            return operation.persist();
        } catch (RuntimeException persistenceFailure) {
            log.error(
                    "Could not persist DEX indexer failure state for deployment {}",
                    deploymentId,
                    persistenceFailure
            );
            return false;
        }
    }

    private String failureMessage(RuntimeException failure) {
        String detail = failure.getMessage();
        String message = detail == null || detail.isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + detail.strip();

        if (message.length() > MAX_STORED_MESSAGE_LENGTH) {
            return message.substring(0, MAX_STORED_MESSAGE_LENGTH);
        }

        return message;
    }

    @FunctionalInterface
    private interface FailurePersistence {
        boolean persist();
    }
}
