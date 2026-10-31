package com.dex.backend.matcher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "dex.matcher",
        name = "enabled",
        havingValue = "true"
)
public class MatcherSchedulingConfiguration {
}
