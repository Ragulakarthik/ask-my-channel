package com.karthik.askmychannel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "channel")
public class Channel {

    @Id
    @Column(name = "channel_id")
    private String channelId;

    @Column(name = "handle", nullable = false)
    private String handle;

    @Column(name = "title")
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Channel() {
        // JPA
    }

    public Channel(String channelId, String handle, String title) {
        this.channelId = channelId;
        this.handle = handle;
        this.title = title;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getHandle() {
        return handle;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
