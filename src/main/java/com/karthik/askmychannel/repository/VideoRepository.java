package com.karthik.askmychannel.repository;

import com.karthik.askmychannel.entity.Video;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoRepository extends JpaRepository<Video, String> {

    long countByChannelId(String channelId);
}
