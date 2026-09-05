package com.karthik.askmychannel.client;

/**
 * Common base for any LLM provider call failure (Gemini, Groq, ...), so callers that fall back
 * across providers can catch one type, and GlobalExceptionHandler can map all of them to the
 * same clean "AI provider unavailable" response regardless of which provider failed.
 */
public class LlmProviderException extends RuntimeException {

    public LlmProviderException(String message) {
        super(message);
    }

    public LlmProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
