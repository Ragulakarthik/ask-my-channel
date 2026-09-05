package com.karthik.askmychannel.service;

import com.karthik.askmychannel.service.model.ChunkData;
import com.karthik.askmychannel.service.model.TranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkingServiceTest {

    private final ChunkingService chunkingService = new ChunkingService();

    @Test
    void groupsSegmentsIntoWindowsAndStartsNewWindowOnceThresholdCrossed() {
        List<TranscriptSegment> segments = List.of(
                new TranscriptSegment("alpha", 0, 5),
                new TranscriptSegment("beta", 10, 5),
                new TranscriptSegment("gamma", 20, 5),
                new TranscriptSegment("delta", 50, 5),
                new TranscriptSegment("epsilon", 60, 5)
        );

        List<ChunkData> chunks = chunkingService.chunk(segments, 45);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).startSeconds()).isEqualTo(0);
        assertThat(chunks.get(0).text()).isEqualTo("alpha beta gamma");
        assertThat(chunks.get(1).startSeconds()).isEqualTo(50);
        assertThat(chunks.get(1).text()).isEqualTo("delta epsilon");
    }

    @Test
    void skipsBlankAndWhitespaceOnlySegmentsWithoutStartingANewWindow() {
        List<TranscriptSegment> segments = List.of(
                new TranscriptSegment("alpha", 0, 5),
                new TranscriptSegment("\n", 5, 0),
                new TranscriptSegment("  ", 6, 0),
                new TranscriptSegment("beta", 10, 5)
        );

        List<ChunkData> chunks = chunkingService.chunk(segments, 45);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("alpha beta");
        assertThat(chunks.get(0).startSeconds()).isEqualTo(0);
    }

    @Test
    void returnsEmptyListForEmptyInput() {
        assertThat(chunkingService.chunk(List.of(), 45)).isEmpty();
    }

    @Test
    void singleSegmentProducesSingleChunk() {
        List<ChunkData> chunks = chunkingService.chunk(
                List.of(new TranscriptSegment("only one", 3.5, 2)), 45);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).startSeconds()).isEqualTo(3.5);
        assertThat(chunks.get(0).text()).isEqualTo("only one");
    }
}
