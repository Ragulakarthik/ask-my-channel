package com.karthik.askmychannel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "ingestionExecutor")
    public Executor ingestionExecutor(AskMyChannelProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.ingestion().maxThreadPoolSize());
        executor.setMaxPoolSize(properties.ingestion().maxThreadPoolSize());
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ingestion-");
        executor.initialize();
        return executor;
    }
}
