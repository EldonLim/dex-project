package com.dex.backend.persistence.indexer;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IndexerCheckpointRepository extends JpaRepository<IndexerCheckpointEntity, String> {
}
