package com.karthik.askmychannel.service;

import com.karthik.askmychannel.client.GeminiClient;
import com.karthik.askmychannel.dto.ChatResponse;
import com.karthik.askmychannel.dto.HistoryTurn;
import com.karthik.askmychannel.entity.Chunk;
import com.karthik.askmychannel.entity.ChunkSource;
import com.karthik.askmychannel.entity.Video;
import com.karthik.askmychannel.repository.ChannelRepository;
import com.karthik.askmychannel.repository.ChunkRepository;
import com.karthik.askmychannel.repository.VideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

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
    private AnswerGenerationService answerGenerationService;
    @Mock
    private ChunkRepository chunkRepository;
    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private VideoRepository videoRepository;

    private ChatService chatService() {
        return new ChatService(geminiClient, answerGenerationService, chunkRepository, channelRepository, videoRepository);
    }

    @Test
    void throwsWhenChannelUnknown() {
        when(channelRepository.existsById("missing-channel")).thenReturn(false);

        assertThatThrownBy(() -> chatService().ask("missing-channel", "what is DSA?", List.of()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void returnsFallbackAnswerWithoutCallingGenerateWhenNoChunksFound() {
        when(channelRepository.existsById("chan-1")).thenReturn(true);
        when(geminiClient.embed("what is DSA?")).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkRepository.findNearest(anyString(), anyString(), anyInt())).thenReturn(List.of());

        ChatResponse response = chatService().ask("chan-1", "what is DSA?", List.of());

        assertThat(response.citations()).isEmpty();
        assertThat(response.answer()).containsIgnoringCase("hasn't been ingested");
        verify(answerGenerationService, never()).generate(anyString());
    }

    @Test
    void buildsPromptFromRetrievedChunksAndFormatsTimestampedCitations() {
        when(channelRepository.existsById("chan-1")).thenReturn(true);
        when(geminiClient.embed("how to prepare for interviews?")).thenReturn(new float[]{0.1f, 0.2f});

        Chunk chunk = new Chunk("chan-1", "vid-1", "prepare DSA and mock interviews daily", 125.0, ChunkSource.TRANSCRIPT, new float[]{0.1f, 0.2f});
        when(chunkRepository.findNearest(anyString(), anyString(), anyInt())).thenReturn(List.of(chunk));
        when(videoRepository.findById("vid-1")).thenReturn(Optional.of(
                new Video("vid-1", "chan-1", "Interview Prep Roadmap", null, 600)));
        when(answerGenerationService.generate(anyString())).thenReturn("Prepare DSA daily and do mock interviews.");

        ChatResponse response = chatService().ask("chan-1", "how to prepare for interviews?", List.of());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(answerGenerationService).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("prepare DSA and mock interviews daily");
        assertThat(promptCaptor.getValue()).contains("how to prepare for interviews?");

        assertThat(response.answer()).isEqualTo("Prepare DSA daily and do mock interviews.");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).videoTitle()).isEqualTo("Interview Prep Roadmap");
        assertThat(response.citations().get(0).url()).isEqualTo("https://youtu.be/vid-1?t=125s");
    }

    @Test
    void deduplicatesCitationsByVideoKeepingTheNearestTimestampPerVideo() {
        when(channelRepository.existsById("chan-1")).thenReturn(true);
        when(geminiClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        // Same video appears 3 times at different timestamps (nearest-first), plus one other video.
        List<Chunk> nearest = List.of(
                new Chunk("chan-1", "vid-1", "excerpt A", 0.0, ChunkSource.TRANSCRIPT, new float[]{0.1f, 0.2f}),
                new Chunk("chan-1", "vid-1", "excerpt B", 274.0, ChunkSource.TRANSCRIPT, new float[]{0.1f, 0.2f}),
                new Chunk("chan-1", "vid-2", "excerpt C", 10.0, ChunkSource.TRANSCRIPT, new float[]{0.1f, 0.2f}),
                new Chunk("chan-1", "vid-1", "excerpt D", 500.0, ChunkSource.TRANSCRIPT, new float[]{0.1f, 0.2f})
        );
        when(chunkRepository.findNearest(anyString(), anyString(), anyInt())).thenReturn(nearest);
        when(videoRepository.findById("vid-1")).thenReturn(Optional.of(new Video("vid-1", "chan-1", "Video One", null, 600)));
        when(videoRepository.findById("vid-2")).thenReturn(Optional.of(new Video("vid-2", "chan-1", "Video Two", null, 600)));
        when(answerGenerationService.generate(anyString())).thenReturn("answer");

        ChatResponse response = chatService().ask("chan-1", "some question", List.of());

        assertThat(response.citations()).hasSize(2);
        assertThat(response.citations().get(0).videoTitle()).isEqualTo("Video One");
        assertThat(response.citations().get(0).url()).isEqualTo("https://youtu.be/vid-1?t=0s");
        assertThat(response.citations().get(1).videoTitle()).isEqualTo("Video Two");
    }

    @Test
    void labelsExcerptsInThePromptByTheirSourceSoTheLlmCanWeighThemDifferently() {
        when(channelRepository.existsById("chan-1")).thenReturn(true);
        when(geminiClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        List<Chunk> nearest = List.of(
                new Chunk("chan-1", "vid-1", "spoken in the video", 40.0, ChunkSource.TRANSCRIPT, new float[]{0.1f, 0.2f}),
                new Chunk("chan-1", "vid-1", "written under the video", 0.0, ChunkSource.DESCRIPTION, new float[]{0.1f, 0.2f}),
                new Chunk("chan-1", "vid-1", "said by a viewer", 0.0, ChunkSource.COMMENT, new float[]{0.1f, 0.2f})
        );
        when(chunkRepository.findNearest(anyString(), anyString(), anyInt())).thenReturn(nearest);
        when(videoRepository.findById("vid-1")).thenReturn(Optional.of(new Video("vid-1", "chan-1", "Some Video", null, 600)));
        when(answerGenerationService.generate(anyString())).thenReturn("answer");

        chatService().ask("chan-1", "some question", List.of());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(answerGenerationService).generate(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("[Video transcript] spoken in the video");
        assertThat(prompt).contains("[Video description] written under the video");
        assertThat(prompt).contains("[Viewer comment] said by a viewer");
    }

    @Test
    void foldsRecentHistoryIntoTheRetrievalQuerySoBareFollowUpsCanStillMatch() {
        when(channelRepository.existsById("chan-1")).thenReturn(true);
        when(geminiClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkRepository.findNearest(anyString(), anyString(), anyInt())).thenReturn(List.of());

        List<HistoryTurn> history = List.of(
                new HistoryTurn("what comment repeated the most?", "\"Will make video on it\" appeared twice."));

        chatService().ask("chan-1", "give me top 5", history);

        // The bare follow-up alone has almost no retrieval signal — the prior question must be
        // folded into what actually gets embedded for the pgvector search.
        verify(geminiClient).embed("what comment repeated the most? give me top 5");
    }

    @Test
    void includesRecentConversationHistoryInTheGenerationPromptForFollowUps() {
        when(channelRepository.existsById("chan-1")).thenReturn(true);
        when(geminiClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        Chunk chunk = new Chunk("chan-1", "vid-1", "some excerpt", 0.0, ChunkSource.COMMENT, new float[]{0.1f, 0.2f});
        when(chunkRepository.findNearest(anyString(), anyString(), anyInt())).thenReturn(List.of(chunk));
        when(videoRepository.findById("vid-1")).thenReturn(Optional.of(new Video("vid-1", "chan-1", "Some Video", null, 600)));
        when(answerGenerationService.generate(anyString())).thenReturn("answer");

        List<HistoryTurn> history = List.of(
                new HistoryTurn("what comment repeated the most?", "\"Will make video on it\" appeared twice."));

        chatService().ask("chan-1", "give me top 5", history);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(answerGenerationService).generate(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("User: what comment repeated the most?");
        assertThat(prompt).contains("Assistant: \"Will make video on it\" appeared twice.");
        assertThat(prompt).contains("Question: give me top 5");
    }

    @Test
    void streamingAskReturnsCitationsAlongsideTheAnswerStream() {
        when(channelRepository.existsById("chan-1")).thenReturn(true);
        when(geminiClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        Chunk chunk = new Chunk("chan-1", "vid-1", "some excerpt", 42.0, ChunkSource.TRANSCRIPT, new float[]{0.1f, 0.2f});
        when(chunkRepository.findNearest(anyString(), anyString(), anyInt())).thenReturn(List.of(chunk));
        when(videoRepository.findById("vid-1")).thenReturn(Optional.of(new Video("vid-1", "chan-1", "Some Video", null, 600)));
        when(answerGenerationService.generateStream(anyString())).thenReturn(Flux.just("Hel", "lo"));

        ChatService.ChatStreamResult result = chatService().askStreaming("chan-1", "some question", List.of());

        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).url()).isEqualTo("https://youtu.be/vid-1?t=42s");
        assertThat(result.answerStream().collectList().block()).containsExactly("Hel", "lo");
    }

    @Test
    void streamingAskReturnsFallbackMessageAsSingleChunkWhenNoChunksFound() {
        when(channelRepository.existsById("chan-1")).thenReturn(true);
        when(geminiClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(chunkRepository.findNearest(anyString(), anyString(), anyInt())).thenReturn(List.of());

        ChatService.ChatStreamResult result = chatService().askStreaming("chan-1", "some question", List.of());

        assertThat(result.citations()).isEmpty();
        assertThat(result.answerStream().collectList().block()).hasSize(1)
                .first().asString().containsIgnoringCase("hasn't been ingested");
    }
}
