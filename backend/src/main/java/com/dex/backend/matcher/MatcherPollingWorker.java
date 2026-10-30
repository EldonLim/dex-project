package com.dex.backend.matcher;

import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.config.DexProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(
        prefix = "dex.matcher",
        name = "enabled",
        havingValue = "true"
)
public class MatcherPollingWorker {

    private static final Logger log =
            LoggerFactory.getLogger(MatcherPollingWorker.class);

    private final MatcherCycleCoordinator coordinator;
    private final String deploymentId;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private final AtomicReference<MatcherWorkerState> state =
            new AtomicReference<>(MatcherWorkerState.STARTING);

    public MatcherPollingWorker(
            MatcherCycleCoordinator coordinator,
            DexProperties properties
    ) {
        this.coordinator = coordinator;
        this.deploymentId = properties.blockchain().deploymentId();
    }

    @Scheduled(
            initialDelayString = "${dex.matcher.poll-interval:2s}",
            fixedDelayString = "${dex.matcher.poll-interval:2s}"
    )
    public void pollOnSchedule() {
        pollOnce();
    }

    public boolean pollOnce() {
        if (halted.get()) {
            log.debug("Skipping DEX matching poll because the worker is halted");
            return false;
        }

        if (!running.compareAndSet(false, true)) {
            log.debug("Skipping overlapping DEX matching poll");
            return false;
        }

        state.set(MatcherWorkerState.RUNNING);

        try {
            MatcherCycleStatus result = coordinator.runCycle();
            state.set(toWorkerState(result));
            return true;
        } catch (MatcherSafetyException exception) {
            halt(exception);
            return true;
        } catch (BlockchainGatewayException exception) {
            if (exception.isRetryable()) {
                retry(exception);
            } else {
                halt(exception);
            }
            return true;
        } catch (RuntimeException exception) {
            retry(exception);
            return true;
        } finally {
            running.set(false);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isHalted() {
        return halted.get();
    }

    public MatcherWorkerState getState() {
        return state.get();
    }

    private MatcherWorkerState toWorkerState(MatcherCycleStatus status) {
        return switch (status) {
            case WAITING_FOR_INDEXER -> MatcherWorkerState.WAITING_FOR_INDEXER;
            case RESERVED, ALREADY_RESERVED -> MatcherWorkerState.RESERVED;
            case IDLE, TEMPORARILY_INELIGIBLE, STALE -> MatcherWorkerState.IDLE;
        };
    }

    private void retry(RuntimeException failure) {
        state.set(MatcherWorkerState.RETRYING);
        log.warn(
                "DEX matching failed for deployment {}; the next cycle will retry: {}",
                deploymentId,
                failureSummary(failure)
        );
    }

    private void halt(RuntimeException failure) {
        halted.set(true);
        state.set(MatcherWorkerState.HALTED);
        log.error(
                "DEX matching halted for deployment {}: {}",
                deploymentId,
                failureSummary(failure)
        );
    }

    private String failureSummary(RuntimeException failure) {
        String detail = failure.getMessage();
        return detail == null || detail.isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + detail.strip();
    }
}
