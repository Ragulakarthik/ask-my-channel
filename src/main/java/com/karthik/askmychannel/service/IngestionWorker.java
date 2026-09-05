package com.karthik.askmychannel.service;

import com.karthik.askmychannel.client.GeminiClient;
import com.karthik.askmychannel.client.YoutubeClient;
import com.karthik.askmychannel.config.AskMyChannelProperties;
import com.karthik.askmychannel.entity.Chunk;
import com.karthik.askmychannel.entity.ChunkSource;
import com.karthik.askmychannel.entity.IngestJob;
import com.karthik.askmychannel.entity.Video;
import com.karthik.askmychannel.repository.ChunkRepository;
import com.karthik.askmychannel.repository.IngestJobRepository;
import com.karthik.askmychannel.repository.VideoRepository;
import com.karthik.askmychannel.service.model.ChannelVideos;
import com.karthik.askmychannel.service.model.ChunkData;
import com.karthik.askmychannel.service.model.VideoContent;
import com.karthik.askmychannel.service.model.VideoMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The actual background ingestion pass: list every video, fetch each transcript, chunk it,
 * embed each chunk, persist. Runs on the "ingestionExecutor" pool (see AsyncConfig).
 * <p>
 * Kept as a separate bean from IngestionService (rather than an @Async method there) because
 * Spring's @Async only intercepts calls that go through the proxy — a self-invocation
 * (this.runIngestion(...)) from within the same class would silently run synchronously.
 */
@Component
public class IngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

    private final YoutubeClient youtubeClient;
    private final ChunkingService chunkingService;
    private final GeminiClient geminiClient;
    private final VideoRepository videoRepository;
    private final IngestJobRepository ingestJobRepository;
    private final ChunkRepository chunkRepository;
    private final AskMyChannelProperties properties;

    public IngestionWorker(YoutubeClient youtubeClient,
                            ChunkingService chunkingService,
                            GeminiClient geminiClient,
                            VideoRepository videoRepository,
                            IngestJobRepository ingestJobRepository,
                            ChunkRepository chunkRepository,
                            AskMyChannelProperties properties) {
        this.youtubeClient = youtubeClient;
        this.chunkingService = chunkingService;
        this.geminiClient = geminiClient;
        this.videoRepository = videoRepository;
        this.ingestJobRepository = ingestJobRepository;
        this.chunkRepository = chunkRepository;
        this.properties = properties;
    }

    @Async("ingestionExecutor")
    public void runIngestion(UUID jobId, String handleOrUrl) {
        IngestJob job = ingestJobRepository.findById(jobId).orElseThrow();
        try {
            ChannelVideos channelVideos = youtubeClient.listVideos(handleOrUrl);
            job.markRunning(channelVideos.videos().size());
            ingestJobRepository.save(job);

            for (VideoMetadata video : channelVideos.videos()) {
                try {
                    ingestVideo(channelVideos.channelId(), video);
                } catch (Exception e) {
                    log.warn("Skipping video {} ({}) due to error: {}", video.videoId(), video.title(), e.getMessage());
                }
                job.incrementVideosDone();
                ingestJobRepository.save(job);
                pauseBetweenVideos();
            }
            job.markDone();
        } catch (Exception e) {
            log.error("Ingestion job {} failed", jobId, e);
            job.markFailed(e.getMessage());
        }
        ingestJobRepository.save(job);
    }

    private void ingestVideo(String channelId, VideoMetadata video) {
        if (!videoRepository.existsById(video.videoId())) {
            videoRepository.save(new Video(video.videoId(), channelId, video.title(), null, video.durationSeconds()));
        }

        // Re-running ingestion on a channel (e.g. to pick up videos skipped by a transient
        // YouTube rate limit last time) must not re-embed and duplicate chunks for videos that
        // already succeeded — this also makes a re-run cheap and fast for anything already done.
        if (chunkRepository.existsByVideoId(video.videoId())) {
            log.debug("Video {} already has chunks, skipping re-ingestion", video.videoId());
            return;
        }

        VideoContent content = youtubeClient.fetchVideoContent(video.videoId());

        if (!content.transcript().isEmpty()) {
            List<ChunkData> chunks = chunkingService.chunk(content.transcript(), properties.ingestion().chunkWindowSeconds());
            for (ChunkData chunkData : chunks) {
                saveChunk(channelId, video.videoId(), chunkData.text(), chunkData.startSeconds(), ChunkSource.TRANSCRIPT);
            }
        }

        if (content.description() != null && !content.description().isBlank()) {
            saveChunk(channelId, video.videoId(), content.description(), 0, ChunkSource.DESCRIPTION);
        }

        for (String comment : content.topComments()) {
            saveChunk(channelId, video.videoId(), comment, 0, ChunkSource.COMMENT);
        }
    }

    private void saveChunk(String channelId, String videoId, String text, double startSeconds, ChunkSource source) {
        float[] embedding = geminiClient.embed(text);
        chunkRepository.save(new Chunk(channelId, videoId, text, startSeconds, source, embedding));
    }

    /**
     * A per-video breather between YouTube caption-track requests. Ingesting many videos
     * back-to-back with no gap was enough to trip YouTube's per-IP rate limit on its caption
     * endpoint mid-run (observed directly: dozens of consecutive HTTP 429s). This, combined
     * with the per-request retry-with-backoff in YoutubeClient, keeps a full-channel ingest
     * well under whatever window that limit uses.
     */
    private void pauseBetweenVideos() {
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
