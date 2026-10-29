package com.dex.backend.api;

import com.dex.backend.config.DexProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiWebConfiguration implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public ApiWebConfiguration(DexProperties properties) {
        this.allowedOrigins = properties.api().corsOrigins().stream()
                .map(Object::toString)
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("Accept", "Content-Type")
                .allowCredentials(false)
                .maxAge(3_600);
    }
}
