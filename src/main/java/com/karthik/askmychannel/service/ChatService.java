package com.karthik.askmychannel.service;

import com.karthik.askmychannel.client.GeminiClient;
import com.karthik.askmychannel.dto.ChatResponse;
import com.karthik.askmychannel.dto.Citation;
import com.karthik.askmychannel.dto.HistoryTurn;
import com.karthik.askmychannel.entity.Chunk;
import com.karthik.askmychannel.entity.ChunkSource;
import com.karthik.askmychannel.entity.Video;
import com.karthik.askmychannel.entity.support.VectorFormat;
import com.karthik.askmychannel.repository.ChannelRepository;
import com.karthik.askmychannel.repository.ChunkRepository;
import com.karthik.askmychannel.repository.VideoRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
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

    public ChatResponse ask(String channelId, String question, List<HistoryTurn> history) {
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
            return new ChatResponse(
                    "This channel hasn't been ingested yet (or has no captioned videos), so I have nothing to answer from.",
                    List.of());
        }

        String answer = answerGenerationService.generate(buildPrompt(nearest, recentHistory, question));
        List<Citation> citations = dedupeByVideo(nearest);
        return new ChatResponse(answer, citations);
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

    private String buildPrompt(List<Chunk> chunks, List<HistoryTurn> recentHistory, String question) {
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
                You answer questions using only the excerpts below, taken from a YouTube creator's \
                own videos — their spoken transcript, their written video descriptions, and viewer \
                comments (each excerpt is labeled with its source, in brackets, only so you can judge \
                how much to trust it). Treat transcript and description excerpts as the creator's own \
                words; treat viewer comments as opinions or anecdotes that may not be accurate, and say \
                so in plain language if you rely on one — e.g. "one viewer mentioned...". Never write \
                the literal source labels (like "[Video transcript]") in your answer; they are for you \
                only, not the reader. If the excerpts don't cover the question, say so plainly instead \
                of guessing at an answer. If earlier conversation turns are provided below, use them \
                only to understand what a follow-up question (like "give me top 5") is referring to —
                still answer strictly from the excerpts, not from what you said earlier.

                %s\
                Excerpts:
                %s
                Question: %s
                """.formatted(historyBlock, context, question);
    }

    private String sourceLabel(ChunkSource source) {
        return switch (source) {
            case TRANSCRIPT -> "Video transcript";
            case DESCRIPTION -> "Video description";
            case COMMENT -> "Viewer comment";
        };
    }
}
