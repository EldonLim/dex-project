package com.dex.backend.persistence.execution;

import com.dex.backend.blockchain.model.Hash256;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionAttemptRepository
        extends JpaRepository<ExecutionAttemptEntity, UUID> {

    Optional<ExecutionAttemptEntity> findByAttemptIdAndDeploymentId(
            UUID attemptId,
            String deploymentId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a
            FROM ExecutionAttemptEntity a
            WHERE a.attemptId = :attemptId
              AND a.deploymentId = :deploymentId
            """)
    Optional<ExecutionAttemptEntity> findForUpdate(
            @Param("attemptId") UUID attemptId,
            @Param("deploymentId") String deploymentId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a
            FROM ExecutionAttemptEntity a
            WHERE a.deploymentId = :deploymentId
              AND a.transactionHash = :transactionHash
            """)
    Optional<ExecutionAttemptEntity> findByTransactionHashForUpdate(
            @Param("deploymentId") String deploymentId,
            @Param("transactionHash") Hash256 transactionHash
    );

    @Query("""
            SELECT a
            FROM ExecutionAttemptEntity a
            WHERE a.deploymentId = :deploymentId
              AND a.status IN :statuses
            ORDER BY a.createdAt ASC, a.attemptId ASC
            """)
    List<ExecutionAttemptEntity> findUnresolvedAttempts(
            @Param("deploymentId") String deploymentId,
            @Param("statuses") Collection<ExecutionAttemptStatus> statuses
    );

    @Query("""
            SELECT a.buyOrderId AS buyOrderId,
                   a.sellOrderId AS sellOrderId
            FROM ExecutionAttemptEntity a
            WHERE a.deploymentId = :deploymentId
              AND a.status IN :statuses
            """)
    List<ReservedOrderPair> findReservedOrderPairs(
            @Param("deploymentId") String deploymentId,
            @Param("statuses") Collection<ExecutionAttemptStatus> statuses
    );

    @Query("""
            SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END
            FROM ExecutionAttemptEntity a
            WHERE a.deploymentId = :deploymentId
              AND a.status IN :statuses
            """)
    boolean existsUnresolved(
            @Param("deploymentId") String deploymentId,
            @Param("statuses") Collection<ExecutionAttemptStatus> statuses
    );
}
