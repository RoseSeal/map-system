package com.whut.map.map_service.llm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class LlmExecutorConfig {

    public static final String LLM_EXECUTOR = "llmExecutor";

    @Bean(name = LLM_EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService llmExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
