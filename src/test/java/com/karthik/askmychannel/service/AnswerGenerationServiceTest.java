package com.karthik.askmychannel.service;

import com.karthik.askmychannel.client.GeminiApiException;
import com.karthik.askmychannel.client.GeminiClient;
import com.karthik.askmychannel.client.GroqApiException;
import com.karthik.askmychannel.client.GroqClient;
import com.karthik.askmychannel.client.LlmProviderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnswerGenerationServiceTest {

    @Mock
    private GroqClient groqClient;
    @Mock
    private GeminiClient geminiClient;

    private AnswerGenerationService service() {
        return new AnswerGenerationService(groqClient, geminiClient);
    }

    @Test
    void usesGroqAndNeverTouchesGeminiWhenGroqSucceeds() {
        when(groqClient.generate("prompt")).thenReturn("groq answer");

        String answer = service().generate("prompt");

        assertThat(answer).isEqualTo("groq answer");
        verify(geminiClient, never()).generate("prompt");
    }

    @Test
    void fallsBackToGeminiWhenGroqFails() {
        when(groqClient.generate("prompt")).thenThrow(new GroqApiException("Groq quota exceeded", null));
        when(geminiClient.generate("prompt")).thenReturn("gemini answer");

        String answer = service().generate("prompt");

        assertThat(answer).isEqualTo("gemini answer");
    }

    @Test
    void throwsACombinedErrorWhenBothProvidersFail() {
        when(groqClient.generate("prompt")).thenThrow(new GroqApiException("Groq down", null));
        when(geminiClient.generate("prompt")).thenThrow(new GeminiApiException("Gemini quota exceeded", null));

        assertThatThrownBy(() -> service().generate("prompt"))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("Groq down")
                .hasMessageContaining("Gemini quota exceeded");
    }
}
