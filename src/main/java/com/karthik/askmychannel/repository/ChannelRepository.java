package com.karthik.askmychannel.repository;

import com.karthik.askmychannel.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChannelRepository extends JpaRepository<Channel, String> {

    Optional<Channel> findByHandle(String handle);
}
