package com.karthik.askmychannel.service;

import com.karthik.askmychannel.client.GeminiClient;
import com.karthik.askmychannel.dto.ChatResponse;
import com.karthik.askmychannel.dto.Citation;
import com.karthik.askmychannel.dto.HistoryTurn;
import com.karthik.askmychannel.entity.Channel;
import com.karthik.askmychannel.entity.Chunk;
import com.karthik.askmychannel.entity.ChunkSource;
import com.karthik.askmychannel.entity.Video;
import com.karthik.askmychannel.entity.support.VectorFormat;
import com.karthik.askmychannel.repository.ChannelRepository;
import com.karthik.askmychannel.repository.ChunkRepository;
import com.karthik.askmychannel.repository.VideoRepository;
import com.karthik.askmychannel.service.support.AnswerStreamParser;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The RAG query path: embed the question, pull the nearest chunks for this channel only,
 * ground the LLM answer in them, and cite back to the source video + timestamp.
 */
@Service
public class ChatService {

    // Retrieve more chunks than we'll cite, since a single well-matched video can otherwise
    // fill the whole top-K with itself at different timestamps — retrieving a wider pool and
    // deduplicating citations by video gives both good grounding and a diverse citation list.
    private static final int RETRIEVAL_K = 8;
    private static final int MAX_CITATIONS = 5;
    private static final int MAX_SUGGESTIONS = 3;

    // The LLM is asked to append follow-up questions after this marker, in the same generation
    // call that produces the answer — one call instead of two, so a free-tier daily quota isn't
    // doubled just for suggestions. The non-streaming path splits on it below; the streaming
    // path (ChatController/frontend) sees the same raw marker arrive as ordinary tokens and
    // splits it out client-side once the marker is fully buffered.
    private static final String SUGGESTIONS_MARKER = AnswerStreamParser.SUGGESTIONS_MARKER;

    // The LLM itself judges whether the retrieved excerpts actually help — not a distance
    // threshold or a keyword list. Both were tried and rejected: calibrating against this app's
    // own data showed real, broadly-phrased on-topic questions (e.g. "give me top 5 mistakes to
    // avoid") land at the same cosine distance as plain greetings like "hi" — distance can't
    // reliably separate the two. Letting the model decide, and signal back via this marker,
    // handles both cases (and everything in between) correctly with no extra API call.
    private static final String UNGROUNDED_MARKER = AnswerStreamParser.UNGROUNDED_MARKER;

    // The server keeps no session state — the client resends recent turns on every request, so
    // a page reload naturally starts a fresh session with no history, without any server-side
    // session store or expiry logic. Capped so a long-running chat doesn't blow up prompt size.
    private static final int MAX_HISTORY_TURNS = 3;

    private final GeminiClient geminiClient;
    private final AnswerGenerationService answerGenerationService;
    private final ChunkRepository chunkRepository;
    private final ChannelRepository channelRepository;
    private final VideoRepository videoRepository;

    public ChatService(GeminiClient geminiClient,
                        AnswerGenerationService answerGenerationService,
                        ChunkRepository chunkRepository,
                        ChannelRepository channelRepository,
                        VideoRepository videoRepository) {
        this.geminiClient = geminiClient;
        this.answerGenerationService = answerGenerationService;
        this.chunkRepository = chunkRepository;
        this.channelRepository = channelRepository;
        this.videoRepository = videoRepository;
    }

    private static final String NOTHING_INGESTED_MESSAGE =
            "This channel hasn't been ingested yet (or has no captioned videos), so I have nothing to answer from.";

    public ChatResponse ask(String channelId, String question, List<HistoryTurn> history) {
        Optional<RetrievalResult> result = retrieveForChannel(channelId, question, history);
        if (result.isEmpty()) {
            return new ChatResponse(NOTHING_INGESTED_MESSAGE, List.of(), List.of());
        }
        String raw = answerGenerationService.generate(result.get().prompt());
        AnswerStreamParser parser = new AnswerStreamParser();
        String answer = parser.onToken(raw) + parser.onComplete();
        List<Citation> citations = parser.isUngrounded() ? List.of() : result.get().citations();
        return new ChatResponse(answer, citations, parser.getSuggestedQuestions());
    }

    /**
     * Streaming counterpart of ask() — same retrieval and prompt-building, but the answer
     * arrives as a Flux of text chunks instead of one blocking String. The citations here are
     * only the *candidate* set (computed from retrieval, before the model has weighed in) —
     * the model itself decides mid-answer whether the excerpts actually apply (see
     * UNGROUNDED_MARKER in buildPrompt), and only the caller (ChatController, via
     * AnswerStreamParser) knows that verdict, so it — not this method — makes the final call on
     * whether to actually send these citations to the client.
     */
    public ChatStreamResult askStreaming(String channelId, String question, List<HistoryTurn> history) {
        Optional<RetrievalResult> result = retrieveForChannel(channelId, question, history);
        if (result.isEmpty()) {
            return new ChatStreamResult(List.of(), Flux.just(NOTHING_INGESTED_MESSAGE));
        }
        Flux<String> answerStream = answerGenerationService.generateStream(result.get().prompt());
        return new ChatStreamResult(result.get().citations(), answerStream);
    }

    private record RetrievalResult(List<Citation> citations, String prompt) {
    }

    public record ChatStreamResult(List<Citation> citations, Flux<String> answerStream) {
    }

    private Optional<RetrievalResult> retrieveForChannel(String channelId, String question, List<HistoryTurn> history) {
        if (!channelRepository.existsById(channelId)) {
            throw new NoSuchElementException("Unknown channel: " + channelId);
        }

        List<HistoryTurn> recentHistory = history.size() > MAX_HISTORY_TURNS
                ? history.subList(history.size() - MAX_HISTORY_TURNS, history.size())
                : history;

        // A bare follow-up like "give me top 5" has almost no retrieval signal on its own —
        // folding in recent questions gives pgvector something to actually match against,
        // while the final answer is still grounded only in the retrieved excerpts + full
        // conversation context built below, not in the retrieval query itself.
        float[] queryVector = geminiClient.embed(buildRetrievalQuery(recentHistory, question));
        List<Chunk> nearest = chunkRepository.findNearest(channelId, VectorFormat.toLiteral(queryVector), RETRIEVAL_K);

        if (nearest.isEmpty()) {
            return Optional.empty();
        }

        String channelTitle = channelRepository.findById(channelId).map(Channel::getTitle).orElse("this channel");
        String prompt = buildPrompt(channelTitle, nearest, recentHistory, question);
        List<Citation> citations = dedupeByVideo(nearest);
        return Optional.of(new RetrievalResult(citations, prompt));
    }

    private String buildRetrievalQuery(List<HistoryTurn> recentHistory, String question) {
        if (recentHistory.isEmpty()) {
            return question;
        }
        StringBuilder combined = new StringBuilder();
        for (HistoryTurn turn : recentHistory) {
            combined.append(turn.question()).append(' ');
        }
        return combined.append(question).toString();
    }

    /**
     * Keeps only the nearest chunk per video (input is already nearest-first from pgvector),
     * so the same video never appears twice in the citation list, then caps to MAX_CITATIONS.
     */
    private List<Citation> dedupeByVideo(List<Chunk> chunks) {
        return chunks.stream()
                .collect(Collectors.toMap(
                        Chunk::getVideoId,
                        chunk -> chunk,
                        (firstSeen, laterDuplicate) -> firstSeen,
                        LinkedHashMap::new))
                .values().stream()
                .map(this::toCitation)
                .limit(MAX_CITATIONS)
                .toList();
    }

    private Citation toCitation(Chunk chunk) {
        String title = videoRepository.findById(chunk.getVideoId())
                .map(Video::getTitle)
                .orElse(chunk.getVideoId());
        String url = "https://youtu.be/" + chunk.getVideoId() + "?t=" + (long) chunk.getStartSeconds() + "s";
        return new Citation(title, url);
    }

    private String buildPrompt(String channelTitle, List<Chunk> chunks, List<HistoryTurn> recentHistory, String question) {
        StringBuilder context = new StringBuilder();
        for (Chunk chunk : chunks) {
            context.append("- [").append(sourceLabel(chunk.getSource())).append("] ")
                    .append(chunk.getText()).append('\n');
        }

        StringBuilder historyBlock = new StringBuilder();
        if (!recentHistory.isEmpty()) {
            historyBlock.append("Earlier in this conversation:\n");
            for (HistoryTurn turn : recentHistory) {
                historyBlock.append("User: ").append(turn.question()).append('\n')
                        .append("Assistant: ").append(turn.answer()).append('\n');
            }
            historyBlock.append('\n');
        }

        return """
                You are the AI agent for "%s"'s YouTube channel. First, judge for yourself: do the \
                excerpts below actually help answer the visitor's question? They're pulled by \
                approximate similarity search, so they won't always be relevant — a greeting like \
                "hi", small talk, or a genuine question about something the channel never covers are \
                all real possibilities, and broad on-topic questions can look just as distant from \
                the excerpts as those do, so use your own judgment rather than assuming a loose match \
                means "unrelated".

                If the excerpts genuinely help: answer using only them, taken from the creator's own \
                videos — their spoken transcript, their written video descriptions, and viewer \
                comments (each excerpt is labeled with its source, in brackets, only so you can judge \
                how much to trust it). Treat transcript and description excerpts as the creator's own \
                words; treat viewer comments as opinions or anecdotes that may not be accurate, and say \
                so in plain language if you rely on one — e.g. "one viewer mentioned...". Never write \
                the literal source labels (like "[Video transcript]") in your answer; they are for you \
                only, not the reader. If earlier conversation turns are provided below, use them only \
                to understand what a follow-up question (like "give me top 5") is referring to — still \
                answer strictly from the excerpts, not from what you said earlier.

                If the excerpts do NOT genuinely help: start your entire response with the exact line \
                %s as the very first thing (nothing before it), then on the next line: if the \
                visitor's message is a greeting or small talk, introduce yourself warmly and briefly \
                (e.g. "Hi, I'm %s's agent — nice to meet you! How can I help you?") without using the \
                word "channel" in your greeting itself; if it's a genuine question unrelated to the \
                channel, answer it briefly and helpfully, then gently mention you're best used for \
                questions about %s's own videos. Never claim an answer comes from the channel's \
                videos when it doesn't.

                After your answer, on a new line write exactly %s followed by up to 3 short follow-up \
                questions a viewer would plausibly ask next, each on its own line starting with "- ". \
                If you used the excerpts, ground these only in topics they could actually answer; if \
                you didn't (the case above), base them on the channel's likely topics instead. Omit \
                the marker entirely if you can't think of any good ones.

                %s\
                Excerpts:
                %s
                Visitor's question: %s
                """.formatted(channelTitle, UNGROUNDED_MARKER, channelTitle, channelTitle,
                SUGGESTIONS_MARKER, historyBlock, context, question);
    }

    private String sourceLabel(ChunkSource source) {
        return switch (source) {
            case TRANSCRIPT -> "Video transcript";
            case DESCRIPTION -> "Video description";
            case COMMENT -> "Viewer comment";
        };
    }
}
