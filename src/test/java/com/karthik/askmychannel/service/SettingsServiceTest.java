package com.karthik.askmychannel.service;

import com.karthik.askmychannel.config.AskMyChannelProperties;
import com.karthik.askmychannel.entity.AppSettings;
import com.karthik.askmychannel.repository.AppSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock
    private AppSettingsRepository repository;

    private final AskMyChannelProperties.Gemini geminiDefaults =
            new AskMyChannelProperties.Gemini("env-gemini-key", "url", "model", 768, "gen-model");
    private final AskMyChannelProperties.Groq groqDefaults =
            new AskMyChannelProperties.Groq("env-groq-key", "url", "model");
    private final AskMyChannelProperties properties = new AskMyChannelProperties(
            geminiDefaults, groqDefaults, new AskMyChannelProperties.Youtube("yt-dlp"),
            new AskMyChannelProperties.Ingestion(45, 2), new AskMyChannelProperties.Profile("secret"));

    private SettingsService service() {
        return new SettingsService(repository, properties);
    }

    @Test
    void fallsBackToEnvVarDefaultsWhenNoSettingsRowExists() {
        when(repository.findById((short) 1)).thenReturn(Optional.empty());

        SettingsService service = service();

        assertThat(service.getChannelHandle()).isEmpty();
        assertThat(service.getEffectiveGeminiApiKey()).isEqualTo("env-gemini-key");
        assertThat(service.getEffectiveGroqApiKey()).isEqualTo("env-groq-key");
        assertThat(service.hasGeminiApiKey()).isTrue();
    }

    @Test
    void dbValueWinsOverEnvVarDefaultWhenPresent() {
        AppSettings stored = new AppSettings("@somehandle", "db-gemini-key", null);
        when(repository.findById((short) 1)).thenReturn(Optional.of(stored));

        SettingsService service = service();

        assertThat(service.getChannelHandle()).contains("@somehandle");
        assertThat(service.getEffectiveGeminiApiKey()).isEqualTo("db-gemini-key");
        // groq wasn't set in the DB row, so it still falls back to the env-var default
        assertThat(service.getEffectiveGroqApiKey()).isEqualTo("env-groq-key");
    }

    @Test
    void updateLeavesFieldsUntouchedWhenBlank() {
        AppSettings existing = new AppSettings("@old-handle", "old-key", "old-groq-key");
        when(repository.findById((short) 1)).thenReturn(Optional.of(existing));

        service().update("@new-handle", "", null);

        ArgumentCaptor<AppSettings> captor = ArgumentCaptor.forClass(AppSettings.class);
        verify(repository).save(captor.capture());
        AppSettings saved = captor.getValue();
        assertThat(saved.getChannelHandle()).isEqualTo("@new-handle");
        assertThat(saved.getGeminiApiKey()).isEqualTo("old-key");
        assertThat(saved.getGroqApiKey()).isEqualTo("old-groq-key");
    }
}
