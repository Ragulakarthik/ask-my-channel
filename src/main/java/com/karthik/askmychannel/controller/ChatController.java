package com.karthik.askmychannel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karthik.askmychannel.dto.ChatRequest;
import com.karthik.askmychannel.dto.ChatResponse;
import com.karthik.askmychannel.entity.Channel;
import com.karthik.askmychannel.repository.ChannelRepository;
import com.karthik.askmychannel.service.ChatService;
import com.karthik.askmychannel.service.support.HandleNormalizer;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/channels")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long STREAM_TIMEOUT_MS = 60_000L;

    private final ChatService chatService;
    private final ChannelRepository channelRepository;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService, ChannelRepository channelRepository, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.channelRepository = channelRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{handle}/chat")
    public ChatResponse chat(@PathVariable String handle, @Valid @RequestBody ChatRequest request) {
        Channel channel = resolveChannel(handle);
        return chatService.ask(channel.getChannelId(), request.question(), request.history());
    }

    /**
     * Same RAG pipeline as /chat, but the answer streams token-by-token over Server-Sent
     * Events instead of waiting for the full response. Citations arrive first as one "citations"
     * event (retrieval already finished by then), followed by a "token" event per chunk of
     * generated text, then the stream closes.
     */
    @PostMapping(value = "/{handle}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable String handle, @Valid @RequestBody ChatRequest request) {
        Channel channel = resolveChannel(handle);
        ChatService.ChatStreamResult result =
                chatService.askStreaming(channel.getChannelId(), request.question(), request.history());

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        try {
            emitter.send(SseEmitter.event().name("citations").data(result.citations()));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        result.answerStream().subscribe(
                token -> {
                    try {
                        // SseEmitter's .data(Object, MediaType) doesn't reliably run a plain
                        // String through Jackson even when APPLICATION_JSON is requested — it
                        // was writing the raw token unquoted (breaking JSON.parse on the client
                        // for any token containing e.g. "**"). Pre-serializing ourselves and
                        // sending the already-valid JSON text as plain data avoids that entirely.
                        emitter.send(SseEmitter.event().name("token").data(objectMapper.writeValueAsString(token)));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    log.warn("Chat stream ended with an error", error);
                    emitter.completeWithError(error);
                },
                emitter::complete);

        return emitter;
    }

    private Channel resolveChannel(String handle) {
        return channelRepository.findByHandle(HandleNormalizer.normalize(handle))
                .orElseThrow(() -> new NoSuchElementException(
                        "Channel '" + handle + "' hasn't been ingested yet — POST /api/channels/" + handle + "/ingest first."));
    }
}
