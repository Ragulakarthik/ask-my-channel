package com.karthik.askmychannel.controller;

import com.karthik.askmychannel.dto.ActiveChannelResponse;
import com.karthik.askmychannel.dto.ProfileResponse;
import com.karthik.askmychannel.dto.ProfileUpdateRequest;
import com.karthik.askmychannel.entity.Channel;
import com.karthik.askmychannel.repository.ChannelRepository;
import com.karthik.askmychannel.service.ProfileAuthService;
import com.karthik.askmychannel.service.SettingsService;
import com.karthik.askmychannel.service.support.HandleNormalizer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private static final String PASSPHRASE_HEADER = "X-Profile-Passphrase";

    private final SettingsService settingsService;
    private final ProfileAuthService profileAuthService;
    private final ChannelRepository channelRepository;

    public ProfileController(SettingsService settingsService, ProfileAuthService profileAuthService,
                              ChannelRepository channelRepository) {
        this.settingsService = settingsService;
        this.profileAuthService = profileAuthService;
        this.channelRepository = channelRepository;
    }

    /**
     * Public, read-only — the chat page calls this on load to know which channel to talk to.
     * No passphrase required: this reveals only a channel handle/title, never a secret.
     */
    @GetMapping("/active-channel")
    public ActiveChannelResponse activeChannel() {
        Optional<String> configuredHandle = settingsService.getChannelHandle();
        if (configuredHandle.isPresent()) {
            String normalized = HandleNormalizer.normalize(configuredHandle.get());
            String title = channelRepository.findByHandle(normalized).map(Channel::getTitle).orElse(null);
            return new ActiveChannelResponse(configuredHandle.get(), title);
        }
        // Fallback for an instance that already has an ingested channel from before this table
        // existed — don't force a re-configure just because app_settings is still empty.
        List<Channel> channels = channelRepository.findAll();
        if (!channels.isEmpty()) {
            Channel first = channels.get(0);
            return new ActiveChannelResponse(first.getHandle(), first.getTitle());
        }
        return new ActiveChannelResponse(null, null);
    }

    @GetMapping
    public ProfileResponse getProfile(@RequestHeader(value = PASSPHRASE_HEADER, required = false) String passphrase) {
        profileAuthService.requirePassphrase(passphrase);
        return new ProfileResponse(
                settingsService.getChannelHandle().orElse(null),
                settingsService.hasGeminiApiKey(),
                settingsService.hasGroqApiKey());
    }

    @PostMapping
    public ProfileResponse updateProfile(@RequestHeader(value = PASSPHRASE_HEADER, required = false) String passphrase,
                                          @RequestBody ProfileUpdateRequest request) {
        profileAuthService.requirePassphrase(passphrase);
        settingsService.update(request.channelHandle(), request.geminiApiKey(), request.groqApiKey());
        return new ProfileResponse(
                settingsService.getChannelHandle().orElse(null),
                settingsService.hasGeminiApiKey(),
                settingsService.hasGroqApiKey());
    }
}
