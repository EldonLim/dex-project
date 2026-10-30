package com.dex.backend.persistence.execution;

import com.dex.backend.blockchain.execution.PreparedSettlement;
import com.dex.backend.blockchain.execution.SettlementRequest;
import com.dex.backend.blockchain.model.EthereumAddress;
import com.dex.backend.blockchain.model.Hash256;
import com.dex.backend.config.BlockchainMode;
import com.dex.backend.config.DexProperties;
import com.dex.backend.matcher.MatchProposal;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionAttemptReadServiceTests {

    private static final String DEPLOYMENT_ID = "read-service-test";
    private static final EthereumAddress EXECUTOR = address(999);

    private final ExecutionAttemptRepository repository =
            mock(ExecutionAttemptRepository.class);
    private final ExecutionAttemptReadService service =
            new ExecutionAttemptReadService(repository, properties());

    @Test
    void emptyDeploymentHasNoWork() {
        when(repository.findUnresolvedAttempts(
                DEPLOYMENT_ID,
                ExecutionAttemptStatus.reservingStatuses()
        )).thenReturn(List.of());

        assertTrue(service.findUnresolvedAttempt().isEmpty());
    }

    @Test
    void mapsReservedAndSignedWorkWithoutExposingJpaEntity() {
        ExecutionAttemptEntity attempt = reservedAttempt();
        when(repository.findUnresolvedAttempts(
                DEPLOYMENT_ID,
                ExecutionAttemptStatus.reservingStatuses()
        )).thenReturn(List.of(attempt));

        ExecutionAttemptWorkItem reserved = service.findUnresolvedAttempt()
                .orElseThrow();
        assertEquals(ExecutionAttemptStatus.RESERVED, reserved.status());
        assertEquals(request(), reserved.request());
        assertTrue(reserved.preparedSettlement() == null);

        PreparedSettlement prepared = prepared();
        attempt.markSigned(prepared, Instant.parse("2026-01-01T00:00:01Z"));
        ExecutionAttemptWorkItem signed = service.findUnresolvedAttempt()
                .orElseThrow();
        assertEquals(ExecutionAttemptStatus.SIGNED, signed.status());
        assertEquals(prepared, signed.preparedSettlement());
    }

    @Test
    void multipleUnresolvedAttemptsAreRejectedAsSafetyFailure() {
        when(repository.findUnresolvedAttempts(
                DEPLOYMENT_ID,
                ExecutionAttemptStatus.reservingStatuses()
        )).thenReturn(List.of(reservedAttempt(), reservedAttempt()));

        assertThrows(
                ExecutionAttemptTransitionException.class,
                service::findUnresolvedAttempt
        );
    }

    private static ExecutionAttemptEntity reservedAttempt() {
        return ExecutionAttemptEntity.reserved(
                UUID.randomUUID(),
                proposal(),
                EXECUTOR,
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    private static MatchProposal proposal() {
        return new MatchProposal(
                DEPLOYMENT_ID,
                BigInteger.ONE,
                BigInteger.TEN,
                BigInteger.valueOf(20),
                BigInteger.valueOf(5),
                BigInteger.valueOf(100)
        );
    }

    private static SettlementRequest request() {
        MatchProposal proposal = proposal();
        return new SettlementRequest(
                proposal.deploymentId(),
                proposal.marketId(),
                proposal.buyOrderId(),
                proposal.sellOrderId(),
                proposal.fillLots()
        );
    }

    private static PreparedSettlement prepared() {
        return new PreparedSettlement(
                request(),
                EXECUTOR,
                BigInteger.ONE.shiftLeft(200),
                new Hash256("0x" + "a".repeat(64)),
                "0xdeadbeef"
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

    private static EthereumAddress address(long value) {
        return new EthereumAddress("0x" + String.format("%040x", value));
    }
}
