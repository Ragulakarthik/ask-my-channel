package com.karthik.askmychannel.service.support;

/**
 * Collapses any of "@handle", "handle", a full channel URL, or a channel URL with "/videos"
 * appended down to one canonical "@handle" form, so a channel ingested via one form of its
 * URL can still be found by chat requests using a different form.
 */
public final class HandleNormalizer {

    private HandleNormalizer() {
    }

    public static String normalize(String handleOrUrl) {
        String trimmed = handleOrUrl.strip();
        String withoutQuery = trimmed.split("[?#]")[0];
        String withoutTrailingSlash = withoutQuery.endsWith("/")
                ? withoutQuery.substring(0, withoutQuery.length() - 1)
                : withoutQuery;
        String withoutVideosSuffix = withoutTrailingSlash.endsWith("/videos")
                ? withoutTrailingSlash.substring(0, withoutTrailingSlash.length() - "/videos".length())
                : withoutTrailingSlash;

        int atIndex = withoutVideosSuffix.lastIndexOf('@');
        String candidate = atIndex >= 0 ? withoutVideosSuffix.substring(atIndex) : withoutVideosSuffix;
        String withAt = candidate.startsWith("@") ? candidate : "@" + candidate;
        return withAt.toLowerCase();
    }
}
