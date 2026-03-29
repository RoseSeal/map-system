package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.client.WhisperClient;
import com.whut.map.map_service.client.WhisperResponse;
import com.whut.map.map_service.config.WhisperProperties;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.FrontendChatPayload;
import com.whut.map.map_service.dto.websocket.InputType;
import com.whut.map.map_service.websocket.ChatMessageFactory;
import com.whut.map.map_service.websocket.WebSocketService;
import com.whut.map.map_service.websocket.validation.AudioPayloadUtils;
import com.whut.map.map_service.websocket.validation.ChatRequestValidator;
import com.whut.map.map_service.websocket.validation.ValidationResult;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.util.Base64;
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
    private final WebSocketService webSocketService;
    private final ChatMessageFactory chatMessageFactory;
    private final ChatRequestValidator chatRequestValidator;
    private final ExecutorService voiceExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public void handleVoice(WebSocketSession session, FrontendChatPayload request) {
        ValidationResult validation = chatRequestValidator.validateSpeechRequest(request);
        if (validation.hasError()) {
            webSocketService.sendToSession(session, validation.errorMessage());
            return;
        }

        String normalizedAudioData = AudioPayloadUtils.normalizeAudioData(request.getAudioData());
        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(normalizedAudioData);
        } catch (IllegalArgumentException e) {
            sendError(session, request, ChatErrorCode.INVALID_AUDIO_FORMAT, "audio_data is not valid Base64 audio.");
            return;
        }

        String language = whisperProperties.getDefaultLanguage();
        String audioFormat = request.getAudioFormat();
        CompletableFuture
                .supplyAsync(() -> whisperClient.transcribe(audioBytes, audioFormat, language), voiceExecutor)
                .orTimeout(whisperProperties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .whenComplete((response, throwable) -> {
                    if (throwable == null) {
                        handleTranscriptionSuccess(session, request, response, language);
                        return;
                    }

                    Throwable cause = unwrap(throwable);
                    ChatErrorCode errorCode = cause instanceof TimeoutException
                            ? ChatErrorCode.TRANSCRIPTION_TIMEOUT
                            : ChatErrorCode.TRANSCRIPTION_FAILED;
                    String errorMessage = cause instanceof TimeoutException
                            ? "Audio transcription timed out."
                            : "Audio transcription failed.";

                    log.warn("Voice transcription failed for session {}, type={}, message={}",
                            session.getId(),
                            cause.getClass().getSimpleName(),
                            cause.getMessage());

                    sendError(session, request, errorCode, errorMessage);
                });
    }

    private void handleTranscriptionSuccess(
            WebSocketSession session,
            FrontendChatPayload request,
            WhisperResponse response,
            String language
    ) {
        String transcript = response == null ? null : response.getText();
        if (!StringUtils.hasText(transcript)) {
            sendError(session, request, ChatErrorCode.TRANSCRIPTION_FAILED, "Audio transcription returned empty text.");
            return;
        }

        String normalizedTranscript = transcript.trim();
        webSocketService.sendToSession(session, chatMessageFactory.buildTranscriptMessage(
                request,
                normalizedTranscript,
                language
        ));

        FrontendChatPayload textRequest = new FrontendChatPayload();
        textRequest.setSequenceId(request.getSequenceId());
        textRequest.setMessageId(request.getMessageId());
        textRequest.setRole(request.getRole());
        textRequest.setInputType(InputType.TEXT);
        textRequest.setContent(normalizedTranscript);
        llmChatService.handleChat(session, textRequest);
    }

    private void sendError(
            WebSocketSession session,
            FrontendChatPayload request,
            ChatErrorCode errorCode,
            String errorMessage
    ) {
        webSocketService.sendToSession(session, chatMessageFactory.buildErrorMessage(
                request == null ? null : request.getSequenceId(),
                request == null ? null : request.getMessageId(),
                errorCode,
                errorMessage
        ));
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    @PreDestroy
    public void shutdown() {
        voiceExecutor.shutdownNow();
    }
}
