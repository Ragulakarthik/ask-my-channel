package com.karthik.askmychannel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "askmychannel")
public record AskMyChannelProperties(Gemini gemini, Youtube youtube, Ingestion ingestion) {

    public record Gemini(
            String apiKey,
            String baseUrl,
            String embeddingModel,
            int embeddingDimensions,
            String generationModel) {
    }

    public record Youtube(String ytDlpPath) {
    }

    public record Ingestion(int chunkWindowSeconds, int maxThreadPoolSize) {
    }
}
