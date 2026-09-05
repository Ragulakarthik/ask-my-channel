package com.karthik.askmychannel.service;

import com.karthik.askmychannel.client.GeminiClient;
import com.karthik.askmychannel.dto.ChatResponse;
import com.karthik.askmychannel.entity.Chunk;
import com.karthik.askmychannel.entity.Video;
import com.karthik.askmychannel.repository.ChannelRepository;
import com.karthik.askmychannel.repository.ChunkRepository;
import com.karthik.askmychannel.repository.VideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private GeminiClient geminiClient;
    @Mock
    private ChunkRepository chunkRepository;
    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private VideoRepository videoRepository;

    private ChatService chatService() {
        return new ChatService(geminiClient, chunkRepository, channelRepository, videoRepository);
    }

    @Test
    void throwsWhenChannelUnknown() {
        when(channelRepository.existsById("missing-channel")).thenReturn(false);

        assertThatThrownBy(() -> chatService().ask("missing-channel", "what is DSA?"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void returnsFallbackAnswerWithoutCallingGenerateWhenNoChunksFound() {
        when(channelRepository.existsById("chan-1")).thenReturn(true);
        when(geminiClient.embed("what is DSA?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkRepository.findNearest(anyString(), anyString(), anyInt())).thenReturn(List.of());

        ChatResponse response = chatService().ask("chan-1", "what is DSA?");

        assertThat(response.citations()).isEmpty();
        assertThat(response.answer()).containsIgnoringCase("hasn't been ingested");
        verify(geminiClient, never()).generate(anyString());
    }

    @Test
    void buildsPromptFromRetrievedChunksAndFormatsTimestampedCitations() {
        when(channelRepository.existsById("chan-1")).thenReturn(true);
        when(geminiClient.embed("how to prepare for interviews?")).thenReturn(new float[]{0.1f, 0.2f});

        Chunk chunk = new Chunk("chan-1", "vid-1", "prepare DSA and mock interviews daily", 125.0, new float[]{0.1f, 0.2f});
        when(chunkRepository.findNearest(anyString(), anyString(), anyInt())).thenReturn(List.of(chunk));
        when(videoRepository.findById("vid-1")).thenReturn(Optional.of(
                new Video("vid-1", "chan-1", "Interview Prep Roadmap", null, 600)));
        when(geminiClient.generate(anyString())).thenReturn("Prepare DSA daily and do mock interviews.");

        ChatResponse response = chatService().ask("chan-1", "how to prepare for interviews?");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(geminiClient).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("prepare DSA and mock interviews daily");
        assertThat(promptCaptor.getValue()).contains("how to prepare for interviews?");

        assertThat(response.answer()).isEqualTo("Prepare DSA daily and do mock interviews.");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).videoTitle()).isEqualTo("Interview Prep Roadmap");
        assertThat(response.citations().get(0).url()).isEqualTo("https://youtu.be/vid-1?t=125s");
    }
}
