package com.karthik.askmychannel.service.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerStreamParserTest {

    private String feed(AnswerStreamParser parser, String... tokens) {
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            out.append(parser.onToken(token));
        }
        out.append(parser.onComplete());
        return out.toString();
    }

    @Test
    void passesThroughPlainTextUntouchedWhenNoMarkersArePresent() {
        AnswerStreamParser parser = new AnswerStreamParser();

        String visible = feed(parser, "Hello ", "there, ", "this is ", "the answer.");

        assertThat(visible).isEqualTo("Hello there, this is the answer.");
        assertThat(parser.isUngrounded()).isFalse();
        assertThat(parser.getSuggestedQuestions()).isEmpty();
    }

    @Test
    void handlesAVeryShortAnswerShorterThanEitherMarker() {
        AnswerStreamParser parser = new AnswerStreamParser();

        String visible = feed(parser, "Hi", "!");

        assertThat(visible).isEqualTo("Hi!");
        assertThat(parser.isUngrounded()).isFalse();
    }

    @Test
    void stripsTheTrailingSuggestionsMarkerAndParsesTheFollowUps() {
        AnswerStreamParser parser = new AnswerStreamParser();

        String visible = feed(parser,
                "Prepare DSA daily.\n===SUGGESTED_FOLLOWUPS===\n- What topics come up most?\n- How long should I practice?\n");

        assertThat(visible).isEqualTo("Prepare DSA daily.\n");
        assertThat(parser.getSuggestedQuestions())
                .containsExactly("What topics come up most?", "How long should I practice?");
        assertThat(parser.isUngrounded()).isFalse();
    }

    @Test
    void stripsTheSuggestionsMarkerEvenWhenSplitAcrossManySeparateTokens() {
        AnswerStreamParser parser = new AnswerStreamParser();

        // Mirrors what Groq/Gemini actually do in practice: the marker text arrives one or two
        // characters at a time rather than as a single contiguous chunk.
        String visible = feed(parser,
                "The answer", " goes here.\n", "=", "=", "=", "SUGGESTED", "_", "FOLLOWUPS", "===",
                "\n- Follow up one\n", "- Follow up two");

        assertThat(visible).isEqualTo("The answer goes here.\n");
        assertThat(parser.getSuggestedQuestions()).containsExactly("Follow up one", "Follow up two");
    }

    @Test
    void detectsTheLeadingUngroundedMarkerAndStripsItPlusOneNewline() {
        AnswerStreamParser parser = new AnswerStreamParser();

        String visible = feed(parser, "===UNGROUNDED===\n", "Hi, I'm the channel's agent!");

        assertThat(parser.isUngrounded()).isTrue();
        assertThat(visible).isEqualTo("Hi, I'm the channel's agent!");
    }

    @Test
    void detectsTheLeadingMarkerEvenWhenSplitAcrossTokens() {
        AnswerStreamParser parser = new AnswerStreamParser();

        String visible = feed(parser, "==", "=UNGROU", "NDED", "===", "\n", "Nice to meet you!");

        assertThat(parser.isUngrounded()).isTrue();
        assertThat(visible).isEqualTo("Nice to meet you!");
    }

    @Test
    void treatsTextThatMerelyStartsSimilarlyToTheMarkerAsOrdinaryAnswerText() {
        AnswerStreamParser parser = new AnswerStreamParser();

        // Starts with "==" like the marker does, but diverges — must not be misdetected.
        String visible = feed(parser, "== ", "just some ", "text with equals signs.");

        assertThat(parser.isUngrounded()).isFalse();
        assertThat(visible).isEqualTo("== just some text with equals signs.");
    }

    @Test
    void handlesBothMarkersTogetherOnAGenuinelyUngroundedAnswer() {
        AnswerStreamParser parser = new AnswerStreamParser();

        String visible = feed(parser,
                "===UNGROUNDED===\nHi, I'm the agent — nice to meet you!\n"
                        + "===SUGGESTED_FOLLOWUPS===\n- What does this channel cover?\n");

        assertThat(parser.isUngrounded()).isTrue();
        assertThat(visible).isEqualTo("Hi, I'm the agent — nice to meet you!\n");
        assertThat(parser.getSuggestedQuestions()).containsExactly("What does this channel cover?");
    }
}
