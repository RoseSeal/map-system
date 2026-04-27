package com.whut.map.map_service.llm.config;

import com.google.genai.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.lang.reflect.Field;

@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class GeminiConfig {

    private final LlmProperties llmProperties;

    @Bean
    public Client geminiClient() {
        Client client = new Client.Builder()
                .apiKey(llmProperties.getGemini().getApiKey())
                .build();

        if (llmProperties.getGemini().getProxy().isEnabled()) {
            configureProxy(client, llmProperties.getGemini().getProxy());
        } else {
            log.info("Gemini client initialized without proxy.");
        }

        return client;
    }

    private void configureProxy(Client client, LlmProperties.ProxyProperties proxyProperties) {
        CloseableHttpClient replacement = buildProxyHttpClient(proxyProperties);
        CloseableHttpClient previous = null;

        try {
            Object apiClient = readField(client, "apiClient");
            previous = (CloseableHttpClient) readField(apiClient, "httpClient");
            writeField(apiClient, "httpClient", replacement);
            log.info("Gemini client initialized with proxy {}://{}:{}.",
                    proxyProperties.getScheme(),
                    proxyProperties.getHost(),
                    proxyProperties.getPort());
        } catch (ReflectiveOperationException | ClassCastException e) {
            closeQuietly(replacement);
            throw new IllegalStateException("Failed to install proxy-enabled HTTP client for Gemini SDK", e);
        }

        closeQuietly(previous);
    }

    private CloseableHttpClient buildProxyHttpClient(LlmProperties.ProxyProperties proxyProperties) {
        int timeoutMs = Math.toIntExact(llmProperties.getTimeoutMs());
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .build();

        HttpHost proxy = new HttpHost(proxyProperties.getHost(), proxyProperties.getPort(), proxyProperties.getScheme());
        return HttpClientBuilder.create()
                .setProxy(proxy)
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    private Object readField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private void writeField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private void closeQuietly(CloseableHttpClient httpClient) {
        if (httpClient == null) {
            return;
        }
        try {
            httpClient.close();
        } catch (IOException e) {
            log.warn("Failed to close Gemini HTTP client: {}", e.getMessage());
        }
    }
}
