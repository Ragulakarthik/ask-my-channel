package com.karthik.askmychannel.controller;

import com.karthik.askmychannel.dto.ChannelSummaryResponse;
import com.karthik.askmychannel.dto.IngestJobResponse;
import com.karthik.askmychannel.dto.IngestStatusResponse;
import com.karthik.askmychannel.entity.IngestJob;
import com.karthik.askmychannel.repository.ChannelRepository;
import com.karthik.askmychannel.repository.VideoRepository;
import com.karthik.askmychannel.service.IngestionService;
import com.karthik.askmychannel.service.ProfileAuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/channels")
public class ChannelController {

    private final IngestionService ingestionService;
    private final ProfileAuthService profileAuthService;
    private final ChannelRepository channelRepository;
    private final VideoRepository videoRepository;

    public ChannelController(IngestionService ingestionService,
                              ProfileAuthService profileAuthService,
                              ChannelRepository channelRepository,
                              VideoRepository videoRepository) {
        this.ingestionService = ingestionService;
        this.profileAuthService = profileAuthService;
        this.channelRepository = channelRepository;
        this.videoRepository = videoRepository;
    }

    @PostMapping("/{handle}/ingest")
    public IngestJobResponse ingest(@PathVariable String handle,
                                     @RequestHeader(value = "X-Profile-Passphrase", required = false) String passphrase) {
        profileAuthService.requirePassphrase(passphrase);
        IngestJob job = ingestionService.startIngestion(handle);
        return new IngestJobResponse(job.getJobId(), job.getStatus());
    }

    @GetMapping("/{handle}/status")
    public IngestStatusResponse status(@PathVariable String handle, @RequestParam UUID jobId) {
        return IngestStatusResponse.from(ingestionService.getStatus(jobId));
    }

    @GetMapping
    public List<ChannelSummaryResponse> listChannels() {
        return channelRepository.findAll().stream()
                .map(channel -> ChannelSummaryResponse.from(channel, videoRepository.countByChannelId(channel.getChannelId())))
                .toList();
    }
}
