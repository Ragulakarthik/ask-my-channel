package com.karthik.askmychannel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karthik.askmychannel.client.LlmProviderException;
import com.karthik.askmychannel.dto.ChatRequest;
import com.karthik.askmychannel.dto.ChatResponse;
import com.karthik.askmychannel.dto.Citation;
import com.karthik.askmychannel.entity.Channel;
import com.karthik.askmychannel.repository.ChannelRepository;
import com.karthik.askmychannel.service.ChatService;
import com.karthik.askmychannel.service.support.AnswerStreamParser;
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
import java.util.List;
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
     * Events instead of waiting for the full response. Raw tokens pass through an
     * AnswerStreamParser, which strips the model's UNGROUNDED_MARKER/SUGGESTED_FOLLOWUPS
     * markers as they arrive so the client only ever sees clean answer text as "token" events.
     * Citations and suggestions are only knowable once the model's verdict is in (did it decide
     * the excerpts were actually relevant?), so — unlike an early design that sent citations
     * before generation even started — they're sent as the last two events, right before the
     * stream closes, correctly reflecting what the answer actually used.
     */
    @PostMapping(value = "/{handle}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable String handle, @Valid @RequestBody ChatRequest request) {
        Channel channel = resolveChannel(handle);
        ChatService.ChatStreamResult result =
                chatService.askStreaming(channel.getChannelId(), request.question(), request.history());

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AnswerStreamParser parser = new AnswerStreamParser();

        result.answerStream().subscribe(
                token -> {
                    try {
                        sendVisibleText(emitter, parser.onToken(token));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    log.warn("Chat stream ended with an error", error);
                    // emitter.completeWithError() would route the failure through the normal
                    // @ExceptionHandler machinery, which tries to write a JSON error body — but
                    // the response's Content-Type is already committed to text/event-stream by
                    // the first "token" event sent above, so that write silently fails
                    // (HttpMessageNotWritableException) and the client just sees the connection
                    // drop. Sending a proper "error" SSE event and completing normally keeps
                    // everything inside the SSE protocol the client already understands.
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(objectMapper.writeValueAsString(friendlyErrorMessage(error))));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                () -> {
                    try {
                        sendVisibleText(emitter, parser.onComplete());
                        List<Citation> citations = parser.isUngrounded() ? List.of() : result.citations();
                        emitter.send(SseEmitter.event().name("citations").data(citations));
                        emitter.send(SseEmitter.event().name("suggestions").data(parser.getSuggestedQuestions()));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });

        return emitter;
    }

    private void sendVisibleText(SseEmitter emitter, String text) throws IOException {
        if (text.isEmpty()) {
            return;
        }
        // SseEmitter's .data(Object, MediaType) doesn't reliably run a plain String through
        // Jackson even when APPLICATION_JSON is requested — it was writing the raw token
        // unquoted (breaking JSON.parse on the client for any token containing e.g. "**").
        // Pre-serializing ourselves and sending the already-valid JSON text as plain data
        // avoids that entirely.
        emitter.send(SseEmitter.event().name("token").data(objectMapper.writeValueAsString(text)));
    }

    private String friendlyErrorMessage(Throwable error) {
        if (error instanceof LlmProviderException) {
            return "The AI provider is temporarily unavailable, please try again shortly.";
        }
        return "Something went wrong while generating the answer, please try again.";
    }

    private Channel resolveChannel(String handle) {
        return channelRepository.findByHandle(HandleNormalizer.normalize(handle))
                .orElseThrow(() -> new NoSuchElementException(
                        "Channel '" + handle + "' hasn't been ingested yet — POST /api/channels/" + handle + "/ingest first."));
    }
}
