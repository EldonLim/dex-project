package com.dex.backend.indexer;

import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Service
public class IndexerOperationalStateService {

    private final IndexerCheckpointRepository checkpointRepository;

    public IndexerOperationalStateService(IndexerCheckpointRepository checkpointRepository) {
        this.checkpointRepository = checkpointRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordTransientFailure(String deploymentId, String message) {
        Optional<IndexerCheckpointEntity> checkpoint = findCheckpoint(deploymentId);

        checkpoint.ifPresent(value -> value.recordFailure(Instant.now(), message));
        return checkpoint.isPresent();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean pause(String deploymentId, String reason) {
        Optional<IndexerCheckpointEntity> checkpoint = findCheckpoint(deploymentId);

        checkpoint.ifPresent(value -> value.pause(Instant.now(), reason));
        return checkpoint.isPresent();
    }

    private Optional<IndexerCheckpointEntity> findCheckpoint(String deploymentId) {
        Objects.requireNonNull(deploymentId, "deploymentId must not be null");

        if (deploymentId.isBlank()) {
            throw new IllegalArgumentException("deploymentId must not be blank");
        }

        return checkpointRepository.findById(deploymentId);
    }
}
