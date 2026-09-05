package com.karthik.askmychannel.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.karthik.askmychannel.config.AskMyChannelProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

/**
 * Thin wrapper over Google's Generative Language REST API (embedContent + generateContent).
 * Deliberately not using Spring AI's abstraction, so the request/response shape stays visible
 * and easy to reason about.
 */
@Component
public class GeminiClient {

    private final WebClient webClient;
    private final AskMyChannelProperties.Gemini properties;

    public GeminiClient(@Qualifier("geminiWebClient") WebClient webClient, AskMyChannelProperties properties) {
        this.webClient = webClient;
        this.properties = properties.gemini();
    }

    /**
     * Embeds text, truncated to the configured dimensionality and L2-normalized (Google
     * recommends normalizing gemini-embedding-001 output whenever a non-default
     * outputDimensionality is requested, since truncated Matryoshka vectors aren't
     * pre-normalized).
     */
    public float[] embed(String text) {
        EmbedContentRequest request = new EmbedContentRequest(
                new EmbedContentRequest.Content(List.of(new EmbedContentRequest.Part(text))),
                properties.embeddingDimensions());

        JsonNode response = post("/models/" + properties.embeddingModel() + ":embedContent", request);
        JsonNode values = response.path("embedding").path("values");

        float[] embedding = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            embedding[i] = (float) values.get(i).asDouble();
        }
        return normalize(embedding);
    }

    public String generate(String prompt) {
        GenerateContentRequest request = new GenerateContentRequest(
                List.of(new GenerateContentRequest.Content(
                        List.of(new GenerateContentRequest.Part(prompt)))));

        JsonNode response = post("/models/" + properties.generationModel() + ":generateContent", request);
        return response.path("candidates").path(0)
                .path("content").path("parts").path(0)
                .path("text").asText("");
    }

    private JsonNode post(String path, Object body) {
        try {
            return webClient.post()
                    .uri(path)
                    .header("X-goog-api-key", properties.apiKey())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                            .filter(GeminiClient::isRetryable))
                    .block();
        } catch (Exception e) {
            // When Retry.backoff exhausts its attempts, reactor throws its own
            // RetryExhaustedException wrapping the real failure as the cause — catching only
            // WebClientResponseException here let that leak out as a raw, unhandled 500 instead
            // of a clean GeminiApiException. Unwrap it (and catch broadly) so nothing escapes.
            Throwable actual = (Exceptions.isRetryExhausted(e) && e.getCause() != null) ? e.getCause() : e;
            if (actual instanceof WebClientResponseException wcre) {
                throw new GeminiApiException("Gemini API call to " + path + " failed: " + wcre.getStatusCode()
                        + " body=" + wcre.getResponseBodyAsString(), wcre);
            }
            throw new GeminiApiException("Gemini API call to " + path + " failed: " + actual.getMessage(), actual);
        }
    }

    /**
     * 429 = rate limited; 503 = "model currently experiencing high demand" — Google's own
     * wording for transient overload on the free tier. Both are worth retrying with backoff;
     * neither indicates a problem with the request itself.
     */
    private static boolean isRetryable(Throwable throwable) {
        return throwable instanceof WebClientResponseException e
                && (e.getStatusCode().value() == 429 || e.getStatusCode().value() == 503);
    }

    private static float[] normalize(float[] vector) {
        double sumSquares = 0;
        for (float v : vector) {
            sumSquares += (double) v * v;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm == 0) {
            return vector;
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }

    private record EmbedContentRequest(Content content, Integer outputDimensionality) {
        private record Content(List<Part> parts) {
        }

        private record Part(String text) {
        }
    }

    private record GenerateContentRequest(List<Content> contents) {
        private record Content(List<Part> parts) {
        }

        private record Part(String text) {
        }
    }
}
