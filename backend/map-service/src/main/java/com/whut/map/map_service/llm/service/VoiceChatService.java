package com.whut.map.map_service.llm.service;

import com.whut.map.map_service.llm.config.LlmExecutorConfig;
import com.whut.map.map_service.llm.config.WhisperProperties;
import com.whut.map.map_service.llm.client.WhisperClient;
import com.whut.map.map_service.llm.dto.WhisperResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceChatService {

    private final WhisperClient whisperClient;
    private final WhisperProperties whisperProperties;
    private final LlmChatService llmChatService;
    @Qualifier(LlmExecutorConfig.LLM_EXECUTOR)
    private final ExecutorService voiceExecutor;

    public record SpeechTranscriptResult(String transcript, String language) {
    }

    public void handleVoice(
            LlmVoiceRequest request,
            Consumer<SpeechTranscriptResult> onTranscript,
            Consumer<LlmChatService.ChatReplyResult> onReply,
            BiConsumer<LlmErrorCode, String> onError
    ) {
        if (!isValidRequest(request)) {
            onError.accept(LlmErrorCode.LLM_FAILED, "Invalid voice request: mode is required.");
            return;
        }

        String language = whisperProperties.getDefaultLanguage();
        String audioFormat = request.audioFormat();
        CompletableFuture<WhisperResponse> future = CompletableFuture
                .supplyAsync(() -> whisperClient.transcribe(request.audioBytes(), audioFormat, language), voiceExecutor);
        future
                .orTimeout(whisperProperties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .whenComplete((response, throwable) -> {
                    if (throwable == null) {
                        handlePostTranscription(request, response, language, onTranscript, onReply, onError);
                        return;
                    }

                    Throwable cause = unwrap(throwable);
                    if (cause instanceof TimeoutException) {
                        future.cancel(true);
                        log.warn("Voice transcription timed out after {} ms.", whisperProperties.getTimeoutMs());
                        onError.accept(LlmErrorCode.TRANSCRIPTION_TIMEOUT, "Audio transcription timed out.");
                        return;
                    }

                    log.warn("Voice transcription failed, type={}, message={}",
                            cause.getClass().getSimpleName(),
                            cause.getMessage());
                    onError.accept(LlmErrorCode.TRANSCRIPTION_FAILED, "Audio transcription failed.");
                });
    }

    private void handlePostTranscription(
            LlmVoiceRequest request,
            WhisperResponse response,
            String language,
            Consumer<SpeechTranscriptResult> onTranscript,
            Consumer<LlmChatService.ChatReplyResult> onReply,
            BiConsumer<LlmErrorCode, String> onError
    ) {
        String transcript = response == null ? null : response.getText();
        if (!StringUtils.hasText(transcript)) {
            onError.accept(LlmErrorCode.TRANSCRIPTION_FAILED, "Audio transcription returned empty text.");
            return;
        }

        String normalizedTranscript = transcript.trim();
        onTranscript.accept(new SpeechTranscriptResult(normalizedTranscript, language));

        switch (request.mode()) {
            case PREVIEW -> {
                return;
            }
            case DIRECT -> forwardTranscriptToLlm(request, normalizedTranscript, onReply, onError);
        }
    }

    private void forwardTranscriptToLlm(
            LlmVoiceRequest request,
            String transcript,
            Consumer<LlmChatService.ChatReplyResult> onReply,
            BiConsumer<LlmErrorCode, String> onError
    ) {
        LlmChatRequest textRequest = buildTextRequestFromTranscript(request, transcript);
        llmChatService.handleChat(textRequest, onReply, onError);
    }

    private LlmChatRequest buildTextRequestFromTranscript(LlmVoiceRequest request, String content) {
        return new LlmChatRequest(
                request.conversationId(),
                request.eventId(),
                content,
                request.selectedTargetIds(),
                false
        );
    }

    private boolean isValidRequest(LlmVoiceRequest request) {
        return request != null && request.mode() != null;
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}
