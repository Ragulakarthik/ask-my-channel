package com.karthik.askmychannel.dto;

import com.karthik.askmychannel.entity.IngestJobStatus;

import java.util.UUID;

public record IngestJobResponse(UUID jobId, IngestJobStatus status) {
}
