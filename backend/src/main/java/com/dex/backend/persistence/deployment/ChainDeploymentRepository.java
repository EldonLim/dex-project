package com.dex.backend.persistence.deployment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChainDeploymentRepository extends JpaRepository<ChainDeploymentEntity, String> {
}
