package com.karthik.askmychannel.client;

public class YoutubeClientException extends RuntimeException {

    public YoutubeClientException(String message) {
        super(message);
    }

    public YoutubeClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
