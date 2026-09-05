package com.karthik.askmychannel.repository;

import com.karthik.askmychannel.entity.IngestJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IngestJobRepository extends JpaRepository<IngestJob, UUID> {
}
