package com.karthik.askmychannel.repository;

import com.karthik.askmychannel.entity.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChunkRepository extends JpaRepository<Chunk, Long> {

    boolean existsByVideoId(String videoId);

    /**
     * Cosine-distance nearest-neighbour search (pgvector's "<=>" operator), scoped to one
     * channel so results never leak across the multi-tenant boundary. queryVectorLiteral is
     * a pgvector text literal, e.g. "[0.1,0.2,...]" (see VectorFormat.toLiteral).
     */
    @Query(value = """
            SELECT * FROM chunk
            WHERE channel_id = :channelId
            ORDER BY embedding <=> CAST(:queryVectorLiteral AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<Chunk> findNearest(@Param("channelId") String channelId,
                             @Param("queryVectorLiteral") String queryVectorLiteral,
                             @Param("topK") int topK);
}
