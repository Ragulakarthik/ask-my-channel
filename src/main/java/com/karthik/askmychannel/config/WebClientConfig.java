package com.karthik.askmychannel.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    @Qualifier("geminiWebClient")
    public WebClient geminiWebClient(AskMyChannelProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.gemini().baseUrl())
                .build();
    }

    @Bean
    @Qualifier("groqWebClient")
    public WebClient groqWebClient(AskMyChannelProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.groq().baseUrl())
                .build();
    }
}
