package com.karthik.askmychannel.service;

import com.karthik.askmychannel.config.AskMyChannelProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Gates the profile page and ingestion-triggering — the app has no login system at all, so this
 * is a deliberately minimal shared-passphrase check, not a full auth framework. Fails closed: if
 * the deployer hasn't set PROFILE_PASSPHRASE, every protected call is rejected rather than the
 * page silently being wide open on a fresh clone.
 */
@Service
public class ProfileAuthService {

    private final String configuredPassphrase;

    public ProfileAuthService(AskMyChannelProperties properties) {
        this.configuredPassphrase = properties.profile().passphrase();
    }

    public void requirePassphrase(String provided) {
        if (configuredPassphrase == null || configuredPassphrase.isBlank()) {
            throw new UnauthorizedException(
                    "Profile access is disabled — set the PROFILE_PASSPHRASE environment variable to enable it.");
        }
        if (provided == null || !constantTimeEquals(configuredPassphrase, provided)) {
            throw new UnauthorizedException("Invalid passphrase.");
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
