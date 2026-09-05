package com.karthik.askmychannel.service;

import com.karthik.askmychannel.config.AskMyChannelProperties;
import com.karthik.askmychannel.entity.AppSettings;
import com.karthik.askmychannel.repository.AppSettingsRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves the "effective" channel handle and API keys: whatever's stored in app_settings if
 * present, otherwise falling back to the env-var-configured defaults. This is what lets an
 * existing env-var-only deployment keep working untouched after this table was introduced, while
 * the profile page can override any of the three fields independently.
 */
@Service
public class SettingsService {

    private static final short ROW_ID = 1;

    private final AppSettingsRepository repository;
    private final AskMyChannelProperties properties;

    public SettingsService(AppSettingsRepository repository, AskMyChannelProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public Optional<String> getChannelHandle() {
        return currentRow().map(AppSettings::getChannelHandle).filter(s -> !s.isBlank());
    }

    public String getEffectiveGeminiApiKey() {
        return currentRow().map(AppSettings::getGeminiApiKey)
                .filter(s -> !s.isBlank())
                .orElse(properties.gemini().apiKey());
    }

    public String getEffectiveGroqApiKey() {
        return currentRow().map(AppSettings::getGroqApiKey)
                .filter(s -> !s.isBlank())
                .orElse(properties.groq().apiKey());
    }

    public boolean hasGeminiApiKey() {
        String key = getEffectiveGeminiApiKey();
        return key != null && !key.isBlank();
    }

    public boolean hasGroqApiKey() {
        String key = getEffectiveGroqApiKey();
        return key != null && !key.isBlank();
    }

    /**
     * Partial update: a blank/null field means "leave this one as it is" — callers never need
     * to resend a key just to change the channel handle, and vice versa.
     */
    public void update(String channelHandle, String geminiApiKey, String groqApiKey) {
        AppSettings settings = currentRow().orElseGet(() -> new AppSettings(null, null, null));
        if (channelHandle != null && !channelHandle.isBlank()) {
            settings.setChannelHandle(channelHandle.strip());
        }
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            settings.setGeminiApiKey(geminiApiKey.strip());
        }
        if (groqApiKey != null && !groqApiKey.isBlank()) {
            settings.setGroqApiKey(groqApiKey.strip());
        }
        repository.save(settings);
    }

    private Optional<AppSettings> currentRow() {
        return repository.findById(ROW_ID);
    }
}
