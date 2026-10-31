package com.dex.backend.matcher;

import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import com.dex.backend.persistence.execution.ExecutionAttemptRepository;
import com.dex.backend.persistence.execution.ExecutionAttemptStatus;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatcherCycleCoordinatorTests {

    private static final String DEPLOYMENT_ID = "cycle-test";
    private static final EthereumAddress EXECUTOR =
            new EthereumAddress("0x" + "e".repeat(40));

    private final MatcherReadinessService readinessService =
            mock(MatcherReadinessService.class);
    private final ExecutionAttemptRepository executionAttemptRepository =
            mock(ExecutionAttemptRepository.class);
    private final ProjectionMatchService projectionMatchService =
            mock(ProjectionMatchService.class);
    private final MatchPreflightService preflightService =
            mock(MatchPreflightService.class);
    private final TemporaryOrderIneligibilityRegistry ineligibilityRegistry =
            mock(TemporaryOrderIneligibilityRegistry.class);
    private final MatchReservationService reservationService =
            mock(MatchReservationService.class);

    @Test
    void waitsWithoutDiscoveryOrRpcWhenIndexerIsNotReady() {
        when(readinessService.isReady()).thenReturn(false);

        assertEquals(
                MatcherCycleStatus.WAITING_FOR_INDEXER,
                coordinator().runCycle()
        );

        verify(executionAttemptRepository, never()).existsUnresolved(
                DEPLOYMENT_ID,
                ExecutionAttemptStatus.reservingStatuses()
        );
        verify(projectionMatchService, never()).findNextProposal();
        verify(preflightService, never()).check(proposal());
    }

    @Test
    void existingUnresolvedAttemptBlocksFurtherDiscovery() {
        when(readinessService.isReady()).thenReturn(true);
        when(executionAttemptRepository.existsUnresolved(
                DEPLOYMENT_ID,
                ExecutionAttemptStatus.reservingStatuses()
        )).thenReturn(true);

        assertEquals(
                MatcherCycleStatus.ALREADY_RESERVED,
                coordinator().runCycle()
        );

        verify(projectionMatchService, never()).findNextProposal();
    }

    @Test
    void noProposalReturnsIdleWithoutRpc() {
        readyWithoutReservation();
        when(projectionMatchService.findNextProposal()).thenReturn(Optional.empty());

        assertEquals(MatcherCycleStatus.IDLE, coordinator().runCycle());

        verify(preflightService, never()).check(proposal());
    }

    @Test
    void failedPreflightMarksOnlyReportedOrdersTemporarilyIneligible() {
        MatchProposal proposal = proposal();
        readyWithoutReservation();
        when(projectionMatchService.findNextProposal()).thenReturn(Optional.of(proposal));
        when(preflightService.check(proposal)).thenReturn(
                MatchPreflightResult.ineligible(Set.of(proposal.sellOrderId()))
        );

        assertEquals(
                MatcherCycleStatus.TEMPORARILY_INELIGIBLE,
                coordinator().runCycle()
        );

        verify(ineligibilityRegistry).markTemporarilyIneligible(
                DEPLOYMENT_ID,
                proposal.sellOrderId()
        );
        verify(reservationService, never()).reserveIfCurrent(
                proposal,
                EXECUTOR
        );
    }

    @Test
    void successfulPreflightDelegatesAtomicReservation() {
        MatchProposal proposal = proposal();
        readyWithoutReservation();
        when(projectionMatchService.findNextProposal()).thenReturn(Optional.of(proposal));
        when(preflightService.check(proposal)).thenReturn(
                MatchPreflightResult.eligible(EXECUTOR)
        );
        when(reservationService.reserveIfCurrent(proposal, EXECUTOR))
                .thenReturn(MatchReservationResult.RESERVED);

        assertEquals(MatcherCycleStatus.RESERVED, coordinator().runCycle());

        verify(reservationService).reserveIfCurrent(proposal, EXECUTOR);
    }

    @Test
    void staleReservationRevalidationReturnsStaleCycle() {
        MatchProposal proposal = proposal();
        readyWithoutReservation();
        when(projectionMatchService.findNextProposal()).thenReturn(Optional.of(proposal));
        when(preflightService.check(proposal)).thenReturn(
                MatchPreflightResult.eligible(EXECUTOR)
        );
        when(reservationService.reserveIfCurrent(proposal, EXECUTOR))
                .thenReturn(MatchReservationResult.STALE);

        assertEquals(MatcherCycleStatus.STALE, coordinator().runCycle());
    }

    private void readyWithoutReservation() {
        when(readinessService.isReady()).thenReturn(true);
        when(executionAttemptRepository.existsUnresolved(
                DEPLOYMENT_ID,
                ExecutionAttemptStatus.reservingStatuses()
        )).thenReturn(false);
    }

    private MatcherCycleCoordinator coordinator() {
        return new MatcherCycleCoordinator(
                readinessService,
                executionAttemptRepository,
                projectionMatchService,
                preflightService,
                ineligibilityRegistry,
                reservationService,
                properties()
        );
    }

    private static MatchProposal proposal() {
        return new MatchProposal(
                DEPLOYMENT_ID,
                BigInteger.ONE,
                BigInteger.TEN,
                BigInteger.valueOf(11),
                BigInteger.valueOf(40),
                BigInteger.valueOf(100)
        );
    }

    private static DexProperties properties() {
        return new DexProperties(
                new DexProperties.Blockchain(
                        BlockchainMode.MOCK,
                        DEPLOYMENT_ID,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                new DexProperties.Indexer(true, Duration.ofSeconds(2), 0, 500),
                new DexProperties.Matcher(
                        true,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(30)
                ),
                new DexProperties.Executor(null, Duration.ofSeconds(2)),
                new DexProperties.Api(List.of(
                        URI.create("http://localhost:5173")
                ))
        );
    }
}
