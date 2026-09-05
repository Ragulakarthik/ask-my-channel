package com.karthik.askmychannel.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karthik.askmychannel.config.AskMyChannelProperties;
import com.karthik.askmychannel.service.SettingsService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.io.IOException;
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
    private final ObjectMapper objectMapper;

    public GroqClient(@Qualifier("groqWebClient") WebClient webClient, AskMyChannelProperties properties,
                       SettingsService settingsService, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.properties = properties.groq();
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    public String generate(String prompt) {
        ChatCompletionRequest request = new ChatCompletionRequest(
                properties.model(),
                List.of(new ChatCompletionRequest.Message("user", prompt)),
                false);

        JsonNode response = post("/chat/completions", request);
        return response.path("choices").path(0).path("message").path("content").asText("");
    }

    /**
     * Streams the answer as it's generated (Groq's chat-completion-chunk SSE format). Reasoning
     * models like gpt-oss emit a separate "reasoning" delta field before the real answer — only
     * "content" deltas are extracted, so the chain-of-thought never reaches the caller. No
     * retry-with-backoff here (unlike the blocking path): a failure mid-stream is handled one
     * level up by AnswerGenerationService falling back to a full, non-streamed Gemini answer.
     */
    public Flux<String> generateStream(String prompt) {
        String apiKey = settingsService.getEffectiveGroqApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.error(new GroqApiException(
                    "No Groq API key configured — set it on the profile page or via the GROQ_API_KEY env var.",
                    null));
        }
        ChatCompletionRequest request = new ChatCompletionRequest(
                properties.model(),
                List.of(new ChatCompletionRequest.Message("user", prompt)),
                true);

        return webClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .mapNotNull(ServerSentEvent::data)
                .takeWhile(data -> !"[DONE]".equals(data))
                .map(this::extractContentDelta)
                .filter(s -> !s.isEmpty())
                .onErrorMap(WebClientResponseException.class, wcre -> new GroqApiException(
                        "Groq streaming call failed: " + wcre.getStatusCode()
                                + " body=" + wcre.getResponseBodyAsString(), wcre));
    }

    private String extractContentDelta(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.path("choices").path(0).path("delta").path("content").asText("");
        } catch (IOException e) {
            return "";
        }
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

    private record ChatCompletionRequest(String model, List<Message> messages, boolean stream) {
        private record Message(String role, String content) {
        }
    }
}
