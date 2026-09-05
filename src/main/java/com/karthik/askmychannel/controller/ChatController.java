package com.karthik.askmychannel.controller;

import com.karthik.askmychannel.dto.ChatRequest;
import com.karthik.askmychannel.dto.ChatResponse;
import com.karthik.askmychannel.entity.Channel;
import com.karthik.askmychannel.repository.ChannelRepository;
import com.karthik.askmychannel.service.ChatService;
import com.karthik.askmychannel.service.support.HandleNormalizer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/channels")
public class ChatController {

    private final ChatService chatService;
    private final ChannelRepository channelRepository;

    public ChatController(ChatService chatService, ChannelRepository channelRepository) {
        this.chatService = chatService;
        this.channelRepository = channelRepository;
    }

    @PostMapping("/{handle}/chat")
    public ChatResponse chat(@PathVariable String handle, @Valid @RequestBody ChatRequest request) {
        Channel channel = channelRepository.findByHandle(HandleNormalizer.normalize(handle))
                .orElseThrow(() -> new NoSuchElementException(
                        "Channel '" + handle + "' hasn't been ingested yet — POST /api/channels/" + handle + "/ingest first."));
        return chatService.ask(channel.getChannelId(), request.question(), request.history());
    }
}
