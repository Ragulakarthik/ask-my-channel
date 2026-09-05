package com.karthik.askmychannel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ingest_job")
public class IngestJob {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "channel_id", nullable = false)
    private String channelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IngestJobStatus status;

    @Column(name = "videos_total", nullable = false)
    private int videosTotal;

    @Column(name = "videos_done", nullable = false)
    private int videosDone;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected IngestJob() {
        // JPA
    }

    public IngestJob(String channelId) {
        this.jobId = UUID.randomUUID();
        this.channelId = channelId;
        this.status = IngestJobStatus.PENDING;
        this.videosTotal = 0;
        this.videosDone = 0;
    }

    public void markRunning(int videosTotal) {
        this.status = IngestJobStatus.RUNNING;
        this.videosTotal = videosTotal;
        touch();
    }

    public void incrementVideosDone() {
        this.videosDone++;
        touch();
    }

    public void markDone() {
        this.status = IngestJobStatus.DONE;
        touch();
    }

    public void markFailed(String errorMessage) {
        this.status = IngestJobStatus.FAILED;
        this.errorMessage = errorMessage;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getJobId() {
        return jobId;
    }

    public String getChannelId() {
        return channelId;
    }

    public IngestJobStatus getStatus() {
        return status;
    }

    public int getVideosTotal() {
        return videosTotal;
    }

    public int getVideosDone() {
        return videosDone;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
