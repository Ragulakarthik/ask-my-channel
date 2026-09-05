package com.karthik.askmychannel.service;

import com.karthik.askmychannel.client.YoutubeClient;
import com.karthik.askmychannel.entity.Channel;
import com.karthik.askmychannel.entity.IngestJob;
import com.karthik.askmychannel.repository.ChannelRepository;
import com.karthik.askmychannel.repository.IngestJobRepository;
import com.karthik.askmychannel.service.model.ChannelVideos;
import com.karthik.askmychannel.service.support.HandleNormalizer;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Handles the synchronous half of ingestion: a fast channel-identity lookup (one video)
 * so the channel/job rows exist and a job id can be returned immediately, then hands off to
 * {@link IngestionWorker} for the slow, potentially many-video background pass.
 */
@Service
public class IngestionService {

    private final YoutubeClient youtubeClient;
    private final ChannelRepository channelRepository;
    private final IngestJobRepository ingestJobRepository;
    private final IngestionWorker ingestionWorker;

    public IngestionService(YoutubeClient youtubeClient,
                             ChannelRepository channelRepository,
                             IngestJobRepository ingestJobRepository,
                             IngestionWorker ingestionWorker) {
        this.youtubeClient = youtubeClient;
        this.channelRepository = channelRepository;
        this.ingestJobRepository = ingestJobRepository;
        this.ingestionWorker = ingestionWorker;
    }

    public IngestJob startIngestion(String handleOrUrl) {
        ChannelVideos resolved = youtubeClient.resolveChannel(handleOrUrl);
        String normalizedHandle = HandleNormalizer.normalize(handleOrUrl);

        // Deliberately not @Transactional: each save() below commits on its own (Spring Data
        // repository methods are individually transactional), so by the time the async worker
        // call fires, both rows are already visible to its own DB connection. Wrapping this
        // method in one transaction would let the async thread start before commit, racing it.
        Channel channel = channelRepository.findById(resolved.channelId())
                .orElseGet(() -> new Channel(resolved.channelId(), normalizedHandle, resolved.channelTitle()));
        channel.setTitle(resolved.channelTitle());
        channelRepository.save(channel);

        IngestJob job = new IngestJob(channel.getChannelId());
        ingestJobRepository.save(job);

        ingestionWorker.runIngestion(job.getJobId(), handleOrUrl);
        return job;
    }

    public IngestJob getStatus(UUID jobId) {
        return ingestJobRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Unknown ingest job: " + jobId));
    }
}
