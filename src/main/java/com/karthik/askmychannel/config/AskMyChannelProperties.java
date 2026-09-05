package com.karthik.askmychannel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "askmychannel")
public record AskMyChannelProperties(Gemini gemini, Groq groq, Youtube youtube, Ingestion ingestion, Profile profile) {

    /**
     * Embeddings, plus a fallback generation model (see AnswerGenerationService) for when
     * Groq — the primary generator — is unavailable. Gemini's generateContent free-tier daily
     * quota (20 requests) turned out to be far stricter than embedContent's, which has its own
     * separate (and, so far, unhit) quota — hence Groq as primary rather than Gemini.
     */
    public record Gemini(
            String apiKey,
            String baseUrl,
            String embeddingModel,
            int embeddingDimensions,
            String generationModel) {
    }

    public record Groq(
            String apiKey,
            String baseUrl,
            String model) {
    }

    public record Youtube(String ytDlpPath) {
    }

    public record Ingestion(int chunkWindowSeconds, int maxThreadPoolSize) {
    }

    /**
     * passphrase gates the profile page (channel/API-key configuration) and triggering
     * ingestion — the app has no login system, so this is a deliberately minimal guard against
     * a random visitor griefing a publicly-reachable instance. Left blank/unset, protected
     * endpoints fail closed rather than defaulting to wide open.
     */
    public record Profile(String passphrase) {
    }
}
