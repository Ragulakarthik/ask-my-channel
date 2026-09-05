package com.karthik.askmychannel.service;

import com.karthik.askmychannel.service.model.ChunkData;
import com.karthik.askmychannel.service.model.TranscriptSegment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups consecutive transcript segments into fixed-duration windows so each resulting chunk
 * carries a single start_seconds timestamp usable for a "jump to this moment" citation link.
 * <p>
 * Pure function, no I/O — fully unit-testable with fabricated segments.
 */
@Service
public class ChunkingService {

    public List<ChunkData> chunk(List<TranscriptSegment> segments, double windowSeconds) {
        List<ChunkData> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        double windowStart = -1;

        for (TranscriptSegment segment : segments) {
            String text = segment.text() == null ? "" : segment.text().strip();
            if (text.isEmpty()) {
                continue;
            }

            if (windowStart < 0) {
                windowStart = segment.startSeconds();
            } else if (segment.startSeconds() - windowStart >= windowSeconds) {
                chunks.add(new ChunkData(buffer.toString().strip(), windowStart));
                buffer.setLength(0);
                windowStart = segment.startSeconds();
            }

            if (!buffer.isEmpty()) {
                buffer.append(' ');
            }
            buffer.append(text);
        }

        if (!buffer.isEmpty()) {
            chunks.add(new ChunkData(buffer.toString().strip(), windowStart));
        }

        return chunks;
    }
}
