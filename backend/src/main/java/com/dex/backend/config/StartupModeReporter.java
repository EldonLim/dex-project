package com.dex.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupModeReporter {

    private static final Logger log =
            LoggerFactory.getLogger(StartupModeReporter.class);

    private final DexProperties properties;

    public StartupModeReporter(DexProperties properties) {
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportMode() {
        if (properties.blockchain().mode() == BlockchainMode.MOCK) {
            log.warn(
                    "DEX backend is running in MOCK mode; no Ethereum RPC transactions will be used"
            );
            return;
        }

        log.info(
                "DEX backend is running in ETHEREUM mode: deploymentId={}, expectedChainId={}, matcherEnabled={}",
                properties.blockchain().deploymentId(),
                properties.blockchain().expectedChainId(),
                properties.matcher().enabled()
        );
    }
}
