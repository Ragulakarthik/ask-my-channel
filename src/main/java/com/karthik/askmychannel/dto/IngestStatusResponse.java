package com.karthik.askmychannel.dto;

import com.karthik.askmychannel.entity.IngestJob;
import com.karthik.askmychannel.entity.IngestJobStatus;

import java.util.UUID;

public record IngestStatusResponse(
        UUID jobId,
        IngestJobStatus status,
        int videosTotal,
        int videosDone,
        String errorMessage) {

    public static IngestStatusResponse from(IngestJob job) {
        return new IngestStatusResponse(
                job.getJobId(),
                job.getStatus(),
                job.getVideosTotal(),
                job.getVideosDone(),
                job.getErrorMessage());
    }
}
