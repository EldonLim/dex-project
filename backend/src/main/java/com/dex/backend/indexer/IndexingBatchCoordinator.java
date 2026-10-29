package com.dex.backend.indexer;

import com.dex.backend.blockchain.BlockchainGateway;
import com.dex.backend.blockchain.event.DexEvent;
import com.dex.backend.blockchain.model.BlockRange;
import com.dex.backend.blockchain.model.BlockReference;
import com.dex.backend.blockchain.model.ChainIdentity;
import com.dex.backend.config.DexProperties;
import com.dex.backend.persistence.indexer.IndexerCheckpointEntity;
import com.dex.backend.persistence.indexer.IndexerCheckpointRepository;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

@Service
public class IndexingBatchCoordinator {

    private final BlockchainGateway blockchainGateway;
    private final DexProperties properties;
    private final DeploymentIdentityService deploymentIdentityService;
    private final IndexerCheckpointRepository checkpointRepository;
    private final IndexingBatchTransactionService transactionService;

    public IndexingBatchCoordinator(
            BlockchainGateway blockchainGateway,
            DexProperties properties,
            DeploymentIdentityService deploymentIdentityService,
            IndexerCheckpointRepository checkpointRepository,
            IndexingBatchTransactionService transactionService
    ) {
        this.blockchainGateway = blockchainGateway;
        this.properties = properties;
        this.deploymentIdentityService = deploymentIdentityService;
        this.checkpointRepository = checkpointRepository;
        this.transactionService = transactionService;
    }

    public IndexingBatchResult indexNextBatch() {
        ChainIdentity identity = blockchainGateway.getChainIdentity();
        verifyConfiguredDeployment(identity);
        verifyDeploymentBlock(identity);

        deploymentIdentityService.ensureRegistered(identity, properties.blockchain().mode());

        BlockReference latestObservedBlock = blockchainGateway.getLatestBlock();
        Optional<IndexerCheckpointEntity> checkpoint = checkpointRepository.findById(
                identity.deploymentId()
        );

        if (checkpoint.map(IndexerCheckpointEntity::isPaused).orElse(false)) {
            return IndexingBatchResult.withoutRange(IndexingBatchStatus.PAUSED);
        }

        checkpoint.ifPresent(this::verifyCheckpointBlock);

        BigInteger eligibleBlockNumber = latestObservedBlock.number().subtract(
                BigInteger.valueOf(properties.indexer().confirmationDepth())
        );

        if (eligibleBlockNumber.compareTo(identity.deploymentBlockNumber()) < 0) {
            return IndexingBatchResult.withoutRange(
                    IndexingBatchStatus.WAITING_FOR_CONFIRMATIONS
            );
        }

        BigInteger fromBlock = checkpoint
                .map(value -> value.getLastProcessedBlockNumber().add(BigInteger.ONE))
                .orElse(identity.deploymentBlockNumber());

        if (fromBlock.compareTo(eligibleBlockNumber) > 0) {
            transactionService.recordSuccessfulObservation(
                    identity.deploymentId(),
                    latestObservedBlock
            );
            return IndexingBatchResult.withoutRange(IndexingBatchStatus.UP_TO_DATE);
        }

        BigInteger batchOffset = BigInteger.valueOf(
                properties.indexer().blockBatchSize() - 1L
        );
        BigInteger toBlock = fromBlock.add(batchOffset).min(eligibleBlockNumber);
        BlockRange range = new BlockRange(fromBlock, toBlock);
        BlockReference processedBlock = requireBlock(toBlock, "batch end");
        List<DexEvent> events = blockchainGateway.getEvents(range);

        BatchEventCounts counts = transactionService.applyBatch(
                identity.deploymentId(),
                range,
                processedBlock,
                latestObservedBlock,
                events
        );

        return IndexingBatchResult.processed(range, counts.applied(), counts.replayed());
    }

    private void verifyConfiguredDeployment(ChainIdentity identity) {
        if (!properties.blockchain().deploymentId().equals(identity.deploymentId())) {
            throw new IndexerSafetyException(
                    "Blockchain gateway deployment ID does not match configured deployment ID"
            );
        }
    }

    private void verifyDeploymentBlock(ChainIdentity identity) {
        BlockReference deploymentBlock = requireBlock(
                identity.deploymentBlockNumber(),
                "deployment"
        );

        if (!deploymentBlock.hash().equals(identity.deploymentBlockHash())) {
            throw new IndexerSafetyException(
                    "Deployment block hash does not match the configured chain identity"
            );
        }
    }

    private void verifyCheckpointBlock(IndexerCheckpointEntity checkpoint) {
        BlockReference checkpointBlock = requireBlock(
                checkpoint.getLastProcessedBlockNumber(),
                "checkpoint"
        );

        if (!checkpointBlock.hash().equals(checkpoint.getLastProcessedBlockHash())) {
            throw new IndexerSafetyException(
                    "Stored checkpoint block hash no longer matches the blockchain"
            );
        }
    }

    private BlockReference requireBlock(BigInteger blockNumber, String purpose) {
        BlockReference block = blockchainGateway.getBlock(blockNumber)
                .orElseThrow(() -> new IndexerSafetyException(
                        "Blockchain gateway could not find the " + purpose
                                + " block " + blockNumber
                ));

        if (!block.number().equals(blockNumber)) {
            throw new IndexerSafetyException(
                    "Blockchain gateway returned the wrong block for " + purpose
            );
        }

        return block;
    }
}
