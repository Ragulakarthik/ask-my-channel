package com.karthik.askmychannel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Single-row table (id is always 1, enforced by a DB check constraint) holding the deployer's
 * configurable channel handle and API keys — the server-side counterpart to what used to be
 * env-var-only configuration, editable from the profile page.
 */
@Entity
@Table(name = "app_settings")
public class AppSettings {

    @Id
    private Short id = 1;

    @Column(name = "channel_handle")
    private String channelHandle;

    @Column(name = "gemini_api_key")
    private String geminiApiKey;

    @Column(name = "groq_api_key")
    private String groqApiKey;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AppSettings() {
        // JPA
    }

    public AppSettings(String channelHandle, String geminiApiKey, String groqApiKey) {
        this.channelHandle = channelHandle;
        this.geminiApiKey = geminiApiKey;
        this.groqApiKey = groqApiKey;
    }

    public Short getId() {
        return id;
    }

    public String getChannelHandle() {
        return channelHandle;
    }

    public void setChannelHandle(String channelHandle) {
        this.channelHandle = channelHandle;
        touch();
    }

    public String getGeminiApiKey() {
        return geminiApiKey;
    }

    public void setGeminiApiKey(String geminiApiKey) {
        this.geminiApiKey = geminiApiKey;
        touch();
    }

    public String getGroqApiKey() {
        return groqApiKey;
    }

    public void setGroqApiKey(String groqApiKey) {
        this.groqApiKey = groqApiKey;
        touch();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
