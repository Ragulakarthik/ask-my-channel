package com.karthik.askmychannel.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karthik.askmychannel.config.AskMyChannelProperties;
import com.karthik.askmychannel.service.model.ChannelVideos;
import com.karthik.askmychannel.service.model.TranscriptSegment;
import com.karthik.askmychannel.service.model.VideoContent;
import com.karthik.askmychannel.service.model.VideoMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Lists a channel's uploads and fetches per-video content (transcript, description, comments).
 * <p>
 * Video listing shells out to the yt-dlp binary (no YouTube Data API key required). Per-video
 * fetching also asks yt-dlp for a video's metadata in one call (which includes the description,
 * top comments, and pre-signed caption-track URLs), then fetches the caption track separately
 * over plain HTTP in the "json3" format — avoiding yt-dlp's own subtitle-download path, which is
 * prone to 429s from YouTube for this use case, and avoiding VTT's roll-up/duplicate-cue text
 * format. If the caption fetch fails, description/comments are still returned rather than
 * losing the whole video.
 */
@Component
public class YoutubeClient {

    private static final Logger log = LoggerFactory.getLogger(YoutubeClient.class);
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(120);

    private final String ytDlpPath;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public YoutubeClient(AskMyChannelProperties properties, ObjectMapper objectMapper) {
        this.ytDlpPath = properties.youtube().ytDlpPath();
        this.objectMapper = objectMapper;
    }

    /**
     * Fast lookup of just the channel's identity (id + title) from its single most recent
     * upload, so a controller can create the channel/job rows and respond immediately,
     * before the full (potentially slow, many-video) listing runs in the background.
     */
    public ChannelVideos resolveChannel(String handleOrUrl) {
        return listVideosInternal(handleOrUrl, 1);
    }

    public ChannelVideos listVideos(String handleOrUrl) {
        return listVideosInternal(handleOrUrl, null);
    }

    private ChannelVideos listVideosInternal(String handleOrUrl, Integer limit) {
        String channelVideosUrl = toChannelVideosUrl(handleOrUrl);
        List<String> args = new ArrayList<>(List.of("--flat-playlist", "--dump-json"));
        if (limit != null) {
            args.add("--playlist-items");
            args.add("1-" + limit);
        }
        args.add(channelVideosUrl);
        List<String> lines = runYtDlp(args);

        List<VideoMetadata> videos = new ArrayList<>();
        String channelId = null;
        String channelTitle = null;

        for (String line : lines) {
            JsonNode entry = parseJsonLine(line);
            if (entry == null) {
                continue;
            }
            if (channelId == null) {
                channelId = entry.path("playlist_channel_id").asText(null);
                channelTitle = entry.path("playlist_channel").asText(null);
            }
            videos.add(new VideoMetadata(
                    entry.path("id").asText(),
                    entry.path("title").asText(null),
                    null,
                    entry.path("duration").isMissingNode() ? null : entry.path("duration").asInt()));
        }

        if (channelId == null) {
            throw new YoutubeClientException("Could not resolve a channel from '" + handleOrUrl
                    + "' — check the handle/URL is correct and the channel has public videos.");
        }

        return new ChannelVideos(channelId, channelTitle, videos);
    }

    private static final int MAX_COMMENTS = 15;

    public VideoContent fetchVideoContent(String videoId) {
        String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
        List<String> lines = runYtDlp(List.of(
                "--skip-download", "--write-comments",
                "--extractor-args", "youtube:max_comments=" + MAX_COMMENTS + ",comment_sort=top",
                "--dump-json", videoUrl));
        JsonNode metadata = parseJsonLine(String.join("", lines));
        if (metadata == null) {
            throw new YoutubeClientException("yt-dlp returned no metadata for video " + videoId);
        }

        String description = metadata.path("description").asText(null);
        List<String> comments = extractTopComments(metadata);

        List<TranscriptSegment> transcript = List.of();
        JsonNode captionUrl = findOriginalCaptionTrackUrl(metadata);
        if (captionUrl == null) {
            log.info("No captions available for video {}", videoId);
        } else {
            try {
                transcript = fetchJson3Transcript(captionUrl.asText());
            } catch (YoutubeClientException e) {
                log.warn("Transcript fetch failed for video {}, keeping description/comments only: {}",
                        videoId, e.getMessage());
            }
        }

        return new VideoContent(transcript, description, comments);
    }

    /**
     * Comments (including the creator's own replies) sorted by like count, most-liked first,
     * capped at MAX_COMMENTS and stripped of trivially short/empty ones.
     */
    private List<String> extractTopComments(JsonNode metadata) {
        List<JsonNode> comments = new ArrayList<>();
        metadata.path("comments").forEach(comments::add);

        return comments.stream()
                .sorted(Comparator.comparingLong((JsonNode c) -> c.path("like_count").asLong(0)).reversed())
                .map(c -> c.path("text").asText("").strip())
                .filter(text -> text.length() > 15)
                .limit(MAX_COMMENTS)
                .toList();
    }

    private JsonNode findOriginalCaptionTrackUrl(JsonNode metadata) {
        JsonNode manual = metadata.path("subtitles");
        if (manual.fields().hasNext()) {
            Iterator<String> names = manual.fieldNames();
            String firstLanguage = names.next();
            return findJson3Url(manual.path(firstLanguage));
        }

        JsonNode automatic = metadata.path("automatic_captions");
        Iterator<String> languageNames = automatic.fieldNames();
        while (languageNames.hasNext()) {
            String language = languageNames.next();
            JsonNode entries = automatic.path(language);
            if (isOriginalLanguageTrack(entries)) {
                return findJson3Url(entries);
            }
        }
        return null;
    }

    /**
     * The original auto-caption language's entries have no "tlang=" URL parameter; every
     * other (translated) language's entries do, since YouTube auto-translates on request.
     */
    private boolean isOriginalLanguageTrack(JsonNode entries) {
        for (JsonNode entry : entries) {
            if (!entry.path("url").asText("").contains("tlang=")) {
                return true;
            }
        }
        return false;
    }

    private JsonNode findJson3Url(JsonNode entries) {
        for (JsonNode entry : entries) {
            if ("json3".equals(entry.path("ext").asText())) {
                return entry.path("url");
            }
        }
        return null;
    }

    private static final int MAX_CAPTION_FETCH_ATTEMPTS = 4;

    // Java's HttpClient sends "User-Agent: Java-http-client/<version>" by default, which is a
    // well-known signature scraping-detection systems watch for. A normal browser UA doesn't
    // make us any less of a bot, but it does mean this specific, easy tell isn't the reason
    // we get flagged.
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private List<TranscriptSegment> fetchJson3Transcript(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", BROWSER_USER_AGENT)
                .GET()
                .build();

        JsonNode root = null;
        for (int attempt = 1; attempt <= MAX_CAPTION_FETCH_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    root = objectMapper.readTree(response.body());
                    break;
                }
                if (response.statusCode() == 429 && attempt < MAX_CAPTION_FETCH_ATTEMPTS) {
                    long backoffMs = 3000L * (1L << (attempt - 1)); // 3s, 6s, 12s
                    log.warn("Caption track fetch got HTTP 429 (attempt {}/{}), backing off {}ms",
                            attempt, MAX_CAPTION_FETCH_ATTEMPTS, backoffMs);
                    sleep(backoffMs);
                    continue;
                }
                throw new YoutubeClientException("Fetching caption track failed with HTTP " + response.statusCode());
            } catch (IOException e) {
                throw new YoutubeClientException("Failed to fetch caption track from " + url, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new YoutubeClientException("Interrupted while fetching caption track from " + url, e);
            }
        }
        if (root == null) {
            throw new YoutubeClientException("Fetching caption track kept returning HTTP 429 after "
                    + MAX_CAPTION_FETCH_ATTEMPTS + " attempts");
        }

        List<TranscriptSegment> segments = new ArrayList<>();
        for (JsonNode event : root.path("events")) {
            long startMs = event.path("tStartMs").asLong(0);
            long durationMs = event.path("dDurationMs").asLong(0);
            StringBuilder text = new StringBuilder();
            for (JsonNode seg : event.path("segs")) {
                text.append(seg.path("utf8").asText(""));
            }
            String cleaned = text.toString().strip();
            if (!cleaned.isEmpty()) {
                segments.add(new TranscriptSegment(cleaned, startMs / 1000.0, durationMs / 1000.0));
            }
        }
        return segments;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YoutubeClientException("Interrupted while backing off from a caption fetch retry", e);
        }
    }

    private String toChannelVideosUrl(String handleOrUrl) {
        String trimmed = handleOrUrl.strip();
        if (trimmed.startsWith("http")) {
            String withoutTrailingSlash = trimmed.endsWith("/")
                    ? trimmed.substring(0, trimmed.length() - 1)
                    : trimmed;
            return withoutTrailingSlash.endsWith("/videos") ? withoutTrailingSlash : withoutTrailingSlash + "/videos";
        }
        String handle = trimmed.startsWith("@") ? trimmed : "@" + trimmed;
        return "https://www.youtube.com/" + handle + "/videos";
    }

    private JsonNode parseJsonLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(line);
        } catch (IOException e) {
            log.warn("Skipping unparseable yt-dlp output line: {}", e.getMessage());
            return null;
        }
    }

    private List<String> runYtDlp(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.addAll(args);

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();
        } catch (IOException e) {
            throw new YoutubeClientException(
                    "Could not launch yt-dlp (path: '" + ytDlpPath + "'). Is it installed and on PATH? "
                            + "Install with: pip install -U yt-dlp", e);
        }

        List<String> outputLines = new ArrayList<>();
        try (BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = stdout.readLine()) != null) {
                outputLines.add(line);
            }
        } catch (IOException e) {
            throw new YoutubeClientException("Failed reading yt-dlp output", e);
        }

        boolean finished;
        try {
            finished = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new YoutubeClientException("Interrupted while waiting for yt-dlp", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new YoutubeClientException("yt-dlp timed out after " + PROCESS_TIMEOUT);
        }
        if (process.exitValue() != 0 && outputLines.isEmpty()) {
            throw new YoutubeClientException("yt-dlp exited with code " + process.exitValue() + " and produced no output");
        }
        return outputLines;
    }
}
