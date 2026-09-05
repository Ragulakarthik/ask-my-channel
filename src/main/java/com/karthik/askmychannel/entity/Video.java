package com.karthik.askmychannel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "video")
public class Video {

    @Id
    @Column(name = "video_id")
    private String videoId;

    @Column(name = "channel_id", nullable = false)
    private String channelId;

    @Column(name = "title")
    private String title;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    protected Video() {
        // JPA
    }

    public Video(String videoId, String channelId, String title, Instant publishedAt, Integer durationSeconds) {
        this.videoId = videoId;
        this.channelId = channelId;
        this.title = title;
        this.publishedAt = publishedAt;
        this.durationSeconds = durationSeconds;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getTitle() {
        return title;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }
}
