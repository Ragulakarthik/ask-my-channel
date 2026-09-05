package com.karthik.askmychannel.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.karthik.askmychannel.config.AskMyChannelProperties;
import com.karthik.askmychannel.service.SettingsService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

/**
 * Thin wrapper over Groq's OpenAI-compatible chat completions API. Free tier, fast, generous
 * rate limits — used as the primary answer-generation provider, with Gemini as a fallback (see
 * AnswerGenerationService) in case Groq's free tier is ever exhausted or unavailable.
 */
@Component
public class GroqClient {

    private final WebClient webClient;
    private final AskMyChannelProperties.Groq properties;
    private final SettingsService settingsService;

    public GroqClient(@Qualifier("groqWebClient") WebClient webClient, AskMyChannelProperties properties,
                       SettingsService settingsService) {
        this.webClient = webClient;
        this.properties = properties.groq();
        this.settingsService = settingsService;
    }

    public String generate(String prompt) {
        ChatCompletionRequest request = new ChatCompletionRequest(
                properties.model(),
                List.of(new ChatCompletionRequest.Message("user", prompt)));

        JsonNode response = post("/chat/completions", request);
        return response.path("choices").path(0).path("message").path("content").asText("");
    }

    private JsonNode post(String path, Object body) {
        String apiKey = settingsService.getEffectiveGroqApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new GroqApiException(
                    "No Groq API key configured — set it on the profile page or via the GROQ_API_KEY env var.",
                    null);
        }
        try {
            return webClient.post()
                    .uri(path)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(2))
                            .filter(GroqClient::isRetryable))
                    .block();
        } catch (Exception e) {
            Throwable actual = (Exceptions.isRetryExhausted(e) && e.getCause() != null) ? e.getCause() : e;
            if (actual instanceof WebClientResponseException wcre) {
                throw new GroqApiException("Groq API call to " + path + " failed: " + wcre.getStatusCode()
                        + " body=" + wcre.getResponseBodyAsString(), wcre);
            }
            throw new GroqApiException("Groq API call to " + path + " failed: " + actual.getMessage(), actual);
        }
    }

    private static boolean isRetryable(Throwable throwable) {
        return throwable instanceof WebClientResponseException e
                && (e.getStatusCode().value() == 429 || e.getStatusCode().value() == 503);
    }

    private record ChatCompletionRequest(String model, List<Message> messages) {
        private record Message(String role, String content) {
        }
    }
}
