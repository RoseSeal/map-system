package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.config.properties.WhisperProperties;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.ChatRequestPayload;
import com.whut.map.map_service.dto.websocket.SpeechRequestPayload;
import com.whut.map.map_service.dto.websocket.SpeechMode;
import com.whut.map.map_service.llm.client.WhisperClient;
import com.whut.map.map_service.llm.dto.WhisperResponse;
import com.whut.map.map_service.service.llm.validation.AudioPayloadUtils;
import com.whut.map.map_service.service.llm.validation.ChatPayloadValidator;
import com.whut.map.map_service.service.llm.validation.ValidationResult;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Base64;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceChatService {

    private final WhisperClient whisperClient;
    private final WhisperProperties whisperProperties;
    private final LlmChatService llmChatService;
    private final ChatPayloadValidator chatPayloadValidator;
    private final ExecutorService voiceExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public record SpeechTranscriptResult(String transcript, String language) {
    }

    public void handleVoice(
            SpeechRequestPayload request,
            Consumer<SpeechTranscriptResult> onTranscript,
            Consumer<LlmChatService.ChatReplyResult> onReply,
            BiConsumer<ChatErrorCode, String> onError
    ) {
        ValidationResult validation = chatPayloadValidator.validateSpeechRequest(request);
        if (validation.hasError()) {
            emitValidationError(validation, onError);
            return;
        }

        String normalizedAudioData = AudioPayloadUtils.normalizeAudioData(request.getAudioData());
        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(normalizedAudioData);
        } catch (IllegalArgumentException e) {
            onError.accept(ChatErrorCode.INVALID_AUDIO_FORMAT, "audio_data is not valid Base64 audio.");
            return;
        }

        String language = whisperProperties.getDefaultLanguage();
        String audioFormat = request.getAudioFormat();
        CompletableFuture
                .supplyAsync(() -> whisperClient.transcribe(audioBytes, audioFormat, language), voiceExecutor)
                .orTimeout(whisperProperties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .whenComplete((response, throwable) -> {
                    if (throwable == null) {
                        handlePostTranscription(request, response, language, onTranscript, onReply, onError);
                        return;
                    }

                    Throwable cause = unwrap(throwable);
                    ChatErrorCode errorCode = cause instanceof TimeoutException
                            ? ChatErrorCode.TRANSCRIPTION_TIMEOUT
                            : ChatErrorCode.TRANSCRIPTION_FAILED;
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
            SpeechRequestPayload request,
            WhisperResponse response,
            String language,
            Consumer<SpeechTranscriptResult> onTranscript,
            Consumer<LlmChatService.ChatReplyResult> onReply,
            BiConsumer<ChatErrorCode, String> onError
    ) {
        String transcript = response == null ? null : response.getText();
        if (!StringUtils.hasText(transcript)) {
            onError.accept(ChatErrorCode.TRANSCRIPTION_FAILED, "Audio transcription returned empty text.");
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
            SpeechRequestPayload request,
            String transcript,
            Consumer<LlmChatService.ChatReplyResult> onReply,
            BiConsumer<ChatErrorCode, String> onError
    ) {
        ChatRequestPayload textRequest = buildTextRequestFromTranscript(request, transcript);
        llmChatService.handleChat(textRequest, onReply, onError);
    }

    private ChatRequestPayload buildTextRequestFromTranscript(SpeechRequestPayload request, String content) {
        return ChatRequestPayload.builder()
                .conversationId(request.getConversationId())
                .eventId(request.getEventId())
                .content(content)
                .selectedTargetIds(request.getSelectedTargetIds())
                .build();
    }

    private boolean isPreviewMode(SpeechRequestPayload request) {
        return request != null && request.getMode() == SpeechMode.PREVIEW;
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private void emitValidationError(
            ValidationResult validationResult,
            BiConsumer<ChatErrorCode, String> onError
    ) {
        onError.accept(validationResult.errorCode(), validationResult.errorMessage());
    }

    @PreDestroy
    public void shutdown() {
        voiceExecutor.shutdownNow();
    }
}
