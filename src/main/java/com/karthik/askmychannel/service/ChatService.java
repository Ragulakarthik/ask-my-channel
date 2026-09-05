package com.karthik.askmychannel.service;

import com.karthik.askmychannel.client.GeminiClient;
import com.karthik.askmychannel.dto.ChatResponse;
import com.karthik.askmychannel.dto.Citation;
import com.karthik.askmychannel.entity.Chunk;
import com.karthik.askmychannel.entity.Video;
import com.karthik.askmychannel.entity.support.VectorFormat;
import com.karthik.askmychannel.repository.ChannelRepository;
import com.karthik.askmychannel.repository.ChunkRepository;
import com.karthik.askmychannel.repository.VideoRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * The RAG query path: embed the question, pull the nearest chunks for this channel only,
 * ground the LLM answer in them, and cite back to the source video + timestamp.
 */
@Service
public class ChatService {

    private static final int TOP_K = 5;

    private final GeminiClient geminiClient;
    private final ChunkRepository chunkRepository;
    private final ChannelRepository channelRepository;
    private final VideoRepository videoRepository;

    public ChatService(GeminiClient geminiClient,
                        ChunkRepository chunkRepository,
                        ChannelRepository channelRepository,
                        VideoRepository videoRepository) {
        this.geminiClient = geminiClient;
        this.chunkRepository = chunkRepository;
        this.channelRepository = channelRepository;
        this.videoRepository = videoRepository;
    }

    public ChatResponse ask(String channelId, String question) {
        if (!channelRepository.existsById(channelId)) {
            throw new NoSuchElementException("Unknown channel: " + channelId);
        }

        float[] queryVector = geminiClient.embed(question);
        List<Chunk> nearest = chunkRepository.findNearest(channelId, VectorFormat.toLiteral(queryVector), TOP_K);

        if (nearest.isEmpty()) {
            return new ChatResponse(
                    "This channel hasn't been ingested yet (or has no captioned videos), so I have nothing to answer from.",
                    List.of());
        }

        String answer = geminiClient.generate(buildPrompt(nearest, question));
        List<Citation> citations = nearest.stream().map(this::toCitation).distinct().toList();
        return new ChatResponse(answer, citations);
    }

    private Citation toCitation(Chunk chunk) {
        String title = videoRepository.findById(chunk.getVideoId())
                .map(Video::getTitle)
                .orElse(chunk.getVideoId());
        String url = "https://youtu.be/" + chunk.getVideoId() + "?t=" + (long) chunk.getStartSeconds() + "s";
        return new Citation(title, url);
    }

    private String buildPrompt(List<Chunk> chunks, String question) {
        StringBuilder context = new StringBuilder();
        for (Chunk chunk : chunks) {
            context.append("- ").append(chunk.getText()).append('\n');
        }
        return """
                You answer questions using only the transcript excerpts below, taken from a YouTube \
                creator's own videos. If the excerpts don't cover the question, say so plainly \
                instead of guessing at an answer.

                Excerpts:
                %s
                Question: %s
                """.formatted(context, question);
    }
}
