package com.karthik.askmychannel.service.support;

import java.util.List;

/**
 * Strips two markers out of a token-by-token LLM answer stream as it arrives, so the client only
 * ever sees clean, human-facing text:
 * <ul>
 *   <li>A leading {@code ===UNGROUNDED===} marker — the model writes this as the very first thing
 *   when it decides the retrieved excerpts don't actually help answer the question (see
 *   {@link com.karthik.askmychannel.service.ChatService}), so the caller knows to withhold
 *   citations for what follows.</li>
 *   <li>A trailing {@code ===SUGGESTED_FOLLOWUPS===} marker, after which the model lists up to a
 *   few follow-up questions as {@code "- question"} lines.</li>
 * </ul>
 * Not thread-safe — one instance per in-flight answer stream, fed tokens strictly in order (which
 * is what a single reactive subscription already guarantees).
 */
public class AnswerStreamParser {

    public static final String UNGROUNDED_MARKER = "===UNGROUNDED===";
    public static final String SUGGESTIONS_MARKER = "===SUGGESTED_FOLLOWUPS===";
    private static final int MAX_SUGGESTIONS = 3;

    private enum Phase { LEADING, BODY, SUGGESTIONS }

    private Phase phase = Phase.LEADING;
    private final StringBuilder leadingBuffer = new StringBuilder();
    private final StringBuilder bodyHoldback = new StringBuilder();
    private final StringBuilder suggestionsRaw = new StringBuilder();
    private boolean ungrounded = false;
    // Set the instant the leading marker is confirmed; the newline right after it (if any) is
    // stripped as soon as enough text has arrived to check — which may not be in the same token
    // the marker itself completed in.
    private boolean pendingLeadingNewlineStrip = false;

    /**
     * Feed the next token from the raw model stream. Returns the portion (possibly empty) that
     * is now safe to show the reader — some tokens are entirely absorbed into holdback buffers
     * and emit nothing until enough has arrived to resolve a marker one way or the other.
     */
    public String onToken(String token) {
        return switch (phase) {
            case LEADING -> onLeadingToken(token);
            case BODY -> onBodyText(token);
            case SUGGESTIONS -> {
                suggestionsRaw.append(token);
                yield "";
            }
        };
    }

    /** Call exactly once after the last token, to flush whatever's still held back. */
    public String onComplete() {
        return switch (phase) {
            // The whole stream ended before the leading buffer could be resolved either way
            // (e.g. a very short answer) — it was never the marker, so it's just answer text.
            case LEADING -> {
                String text = leadingBuffer.toString();
                leadingBuffer.setLength(0);
                phase = Phase.BODY;
                yield onBodyText(text);
            }
            case BODY -> {
                String text = bodyHoldback.toString();
                bodyHoldback.setLength(0);
                yield text;
            }
            case SUGGESTIONS -> "";
        };
    }

    public boolean isUngrounded() {
        return ungrounded;
    }

    public List<String> getSuggestedQuestions() {
        return suggestionsRaw.toString().lines()
                .map(String::strip)
                .filter(line -> line.startsWith("-"))
                .map(line -> line.substring(1).strip())
                .filter(line -> !line.isBlank())
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    private String onLeadingToken(String token) {
        leadingBuffer.append(token);
        String buffered = leadingBuffer.toString();

        if (isPrefixOfMarker(buffered, UNGROUNDED_MARKER)) {
            if (buffered.length() < UNGROUNDED_MARKER.length()) {
                return ""; // still ambiguous, keep buffering
            }
            ungrounded = true;
            String rest = buffered.substring(UNGROUNDED_MARKER.length());
            leadingBuffer.setLength(0);
            phase = Phase.BODY;
            pendingLeadingNewlineStrip = true;
            return onBodyText(rest);
        }

        // Diverged from the marker — this was never going to be it, flush as ordinary text.
        leadingBuffer.setLength(0);
        phase = Phase.BODY;
        return onBodyText(buffered);
    }

    private String onBodyText(String text) {
        if (pendingLeadingNewlineStrip && !text.isEmpty()) {
            pendingLeadingNewlineStrip = false;
            if (text.startsWith("\n")) {
                text = text.substring(1);
            }
        }
        bodyHoldback.append(text);
        String buffered = bodyHoldback.toString();
        int markerIndex = buffered.indexOf(SUGGESTIONS_MARKER);
        if (markerIndex != -1) {
            String toEmit = buffered.substring(0, markerIndex);
            suggestionsRaw.append(buffered.substring(markerIndex + SUGGESTIONS_MARKER.length()));
            bodyHoldback.setLength(0);
            phase = Phase.SUGGESTIONS;
            return toEmit;
        }
        // Hold back the marker's length-1 trailing characters in case it's split across the next
        // token(s) — the same technique the frontend used to use for this, now done once here.
        int safeLength = Math.max(0, buffered.length() - (SUGGESTIONS_MARKER.length() - 1));
        String toEmit = buffered.substring(0, safeLength);
        bodyHoldback.delete(0, safeLength);
        return toEmit;
    }

    private static boolean isPrefixOfMarker(String buffered, String marker) {
        int len = Math.min(buffered.length(), marker.length());
        return marker.regionMatches(0, buffered, 0, len);
    }
}
