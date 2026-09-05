package com.karthik.askmychannel.entity;

import com.karthik.askmychannel.entity.support.VectorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Type;

@Entity
@Table(name = "chunk")
public class Chunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private String channelId;

    @Column(name = "video_id", nullable = false)
    private String videoId;

    @Column(name = "text", nullable = false)
    private String text;

    @Column(name = "start_seconds", nullable = false)
    private double startSeconds;

    @Type(VectorType.class)
    @Column(name = "embedding", nullable = false, columnDefinition = "vector(768)")
    private float[] embedding;

    protected Chunk() {
        // JPA
    }

    public Chunk(String channelId, String videoId, String text, double startSeconds, float[] embedding) {
        this.channelId = channelId;
        this.videoId = videoId;
        this.text = text;
        this.startSeconds = startSeconds;
        this.embedding = embedding;
    }

    public Long getId() {
        return id;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getText() {
        return text;
    }

    public double getStartSeconds() {
        return startSeconds;
    }

    public float[] getEmbedding() {
        return embedding;
    }
}
