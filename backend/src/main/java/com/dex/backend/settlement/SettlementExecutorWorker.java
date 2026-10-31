package com.dex.backend.settlement;

import com.dex.backend.blockchain.BlockchainGatewayException;
import com.dex.backend.config.DexProperties;
import com.dex.backend.persistence.execution.ExecutionAttemptTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
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
public class SettlementExecutorWorker {

    private static final Logger log =
            LoggerFactory.getLogger(SettlementExecutorWorker.class);

    private final SettlementExecutionCoordinator coordinator;
    private final String deploymentId;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private final AtomicReference<SettlementExecutorWorkerState> state =
            new AtomicReference<>(SettlementExecutorWorkerState.STARTING);

    public SettlementExecutorWorker(
            SettlementExecutionCoordinator coordinator,
            DexProperties properties
    ) {
        this.coordinator = coordinator;
        this.deploymentId = properties.blockchain().deploymentId();
    }

    @Scheduled(
            initialDelayString = "${dex.executor.poll-interval:2s}",
            fixedDelayString = "${dex.executor.poll-interval:2s}"
    )
    public void pollOnSchedule() {
        pollOnce();
    }

    public boolean pollOnce() {
        if (halted.get()) {
            log.debug(
                    "Skipping settlement executor poll because it is halted"
            );
            return false;
        }

        if (!running.compareAndSet(false, true)) {
            log.debug("Skipping overlapping settlement executor poll");
            return false;
        }

        state.set(SettlementExecutorWorkerState.RUNNING);

        try {
            state.set(toWorkerState(coordinator.runCycle()));
            return true;
        } catch (BlockchainGatewayException exception) {
            if (exception.isRetryable()) {
                retry(exception);
            } else {
                halt(exception);
            }
            return true;
        } catch (SettlementExecutorSafetyException
                 | ExecutionAttemptTransitionException
                 | IllegalArgumentException exception) {
            halt(exception);
            return true;
        } catch (DataAccessException exception) {
            retry(exception);
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

    public SettlementExecutorWorkerState getState() {
        return state.get();
    }

    private SettlementExecutorWorkerState toWorkerState(
            SettlementExecutorCycleStatus status
    ) {
        return switch (status) {
            case IDLE -> SettlementExecutorWorkerState.IDLE;
            case SIGNED -> SettlementExecutorWorkerState.SIGNED;
            case WAITING_FOR_RECONCILIATION ->
                    SettlementExecutorWorkerState.WAITING_FOR_RECONCILIATION;
        };
    }

    private void retry(RuntimeException failure) {
        state.set(SettlementExecutorWorkerState.RETRYING);
        log.warn(
                "Settlement execution will retry for deployment {} after {}",
                deploymentId,
                failure.getClass().getSimpleName()
        );
    }

    private void halt(RuntimeException failure) {
        halted.set(true);
        state.set(SettlementExecutorWorkerState.HALTED);
        log.error(
                "Settlement execution halted for deployment {} after {}",
                deploymentId,
                failure.getClass().getSimpleName()
        );
    }
}
