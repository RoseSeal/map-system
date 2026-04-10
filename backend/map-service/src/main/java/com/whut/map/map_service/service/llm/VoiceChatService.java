package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.config.properties.WhisperProperties;
import com.whut.map.map_service.llm.client.WhisperClient;
import com.whut.map.map_service.llm.dto.WhisperResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Base64;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.whut.map.map_service.config.llm.LlmExecutorConfig.LLM_EXECUTOR;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceChatService {

    private final WhisperClient whisperClient;
    private final WhisperProperties whisperProperties;
    private final LlmChatService llmChatService;
    @Qualifier(LLM_EXECUTOR)
    private final ExecutorService voiceExecutor;

    public record SpeechTranscriptResult(String transcript, String language) {
    }

    public void handleVoice(
            LlmVoiceRequest request,
            Consumer<SpeechTranscriptResult> onTranscript,
            Consumer<LlmChatService.ChatReplyResult> onReply,
            BiConsumer<LlmErrorCode, String> onError
    ) {
        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(request.audioData());
        } catch (IllegalArgumentException e) {
            onError.accept(LlmErrorCode.TRANSCRIPTION_FAILED, "Invalid audio data encoding.");
            return;
        }

        String language = whisperProperties.getDefaultLanguage();
        String audioFormat = request.audioFormat();
        CompletableFuture
                .supplyAsync(() -> whisperClient.transcribe(audioBytes, audioFormat, language), voiceExecutor)
                .orTimeout(whisperProperties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .whenComplete((response, throwable) -> {
                    if (throwable == null) {
                        handlePostTranscription(request, response, language, onTranscript, onReply, onError);
                        return;
                    }

                    Throwable cause = unwrap(throwable);
                    LlmErrorCode errorCode = cause instanceof TimeoutException
                            ? LlmErrorCode.TRANSCRIPTION_TIMEOUT
                            : LlmErrorCode.TRANSCRIPTION_FAILED;
                    String errorMessage = cause instanceof TimeoutException
                            ? "Audio transcription timed out."
                            : "Audio transcription failed.";

                    log.warn("Voice transcription failed, type={}, message={}",
                            cause.getClass().getSimpleName(),
                            cause.getMessage());

                    onError.accept(errorCode, errorMessage);
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

        if (isPreviewMode(request)) {
            return;
        }

        forwardTranscriptToLlm(request, normalizedTranscript, onReply, onError);
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
                request.selectedTargetIds()
        );
    }

    private boolean isPreviewMode(LlmVoiceRequest request) {
        return request != null && request.mode() == LlmVoiceMode.PREVIEW;
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}
