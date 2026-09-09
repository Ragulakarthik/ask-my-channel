package com.karthik.askmychannel.service;

import com.karthik.askmychannel.client.GeminiClient;
import com.karthik.askmychannel.client.GroqClient;
import com.karthik.askmychannel.client.LlmProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Generates the final answer text, trying Groq first (fast, generous free-tier limits) and
 * falling back to Gemini if Groq fails for any reason — quota exhaustion, an outage, whatever.
 * Both are free-tier APIs with independent quotas, so a fallback meaningfully improves uptime
 * at zero extra cost. Embeddings are unaffected by this — they stay on Gemini directly, since
 * Groq has no embeddings endpoint.
 */
@Service
public class AnswerGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AnswerGenerationService.class);

    private final GroqClient groqClient;
    private final GeminiClient geminiClient;

    public AnswerGenerationService(GroqClient groqClient, GeminiClient geminiClient) {
        this.groqClient = groqClient;
        this.geminiClient = geminiClient;
    }

    public String generate(String prompt) {
        try {
            return groqClient.generate(prompt);
        } catch (LlmProviderException groqFailure) {
            log.warn("Groq generation failed, falling back to Gemini: {}", groqFailure.getMessage());
            try {
                return geminiClient.generate(prompt);
            } catch (LlmProviderException geminiFailure) {
                throw new LlmProviderException(
                        "Both Groq and Gemini failed to generate an answer. Groq: " + groqFailure.getMessage()
                                + " | Gemini: " + geminiFailure.getMessage(),
                        geminiFailure);
            }
        }
    }

    /**
     * Streaming counterpart: tokens flow from Groq as they're generated. If Groq's stream
     * errors (quota, outage, ...) at any point — including immediately, before any token
     * arrived — falls back to Gemini's blocking generate(), surfaced to the caller as a single
     * emitted chunk rather than a token-by-token stream. A degraded (non-streamed) fallback
     * answer beats no answer at all.
     * <p>
     * Groq's error signal arrives on the reactor-netty event-loop thread that made the HTTP
     * call — a thread Reactor forbids blocking on. geminiClient.generate() calls .block()
     * internally, so the fallback must run on a scheduler meant for blocking work
     * (boundedElastic) rather than directly on that thread, or it throws IllegalStateException
     * and breaks the whole SSE response instead of falling back.
     */
    public Flux<String> generateStream(String prompt) {
        return groqClient.generateStream(prompt)
                .onErrorResume(LlmProviderException.class, groqFailure -> {
                    log.warn("Groq streaming failed, falling back to Gemini (non-streamed): {}",
                            groqFailure.getMessage());
                    return Mono.fromCallable(() -> geminiClient.generate(prompt))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flux();
                });
    }
}
