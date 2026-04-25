package com.whut.map.map_service.llm.service;

import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.config.WhisperProperties;
import com.whut.map.map_service.llm.client.WhisperClient;
import com.whut.map.map_service.llm.context.ExplanationCache;
import com.whut.map.map_service.llm.context.RiskContextFormatter;
import com.whut.map.map_service.llm.context.RiskContextHolder;
import com.whut.map.map_service.llm.memory.ConversationMemory;
import com.whut.map.map_service.llm.dto.WhisperResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceChatServiceTest {

    private final WhisperProperties whisperProperties = new WhisperProperties();

    @Test
    void previewModeReturnsTranscriptWithoutForwardingToChat() throws Exception {
        RecordingWhisperClient whisperClient = new RecordingWhisperClient();
        RecordingLlmChatService llmChatService = new RecordingLlmChatService();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            VoiceChatService service = new VoiceChatService(
                    whisperClient,
                    whisperProperties,
                    llmChatService,
                    executor
            );

            WhisperResponse response = new WhisperResponse();
            response.setText("甲板前方目标接近");
            whisperClient.response = response;
            LlmVoiceRequest request = new LlmVoiceRequest(
                    "conversation-1",
                    "event-1",
                    "test".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "webm",
                    LlmVoiceMode.PREVIEW,
                    java.util.List.of("target-1")
            );

            CapturingSpeechCallback callback = new CapturingSpeechCallback();
            service.handleVoice(request, callback::captureTranscript, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(callback.errorCode()).isNull();
            assertThat(callback.errorMessage()).isNull();
            assertThat(callback.reply()).isNull();
            assertThat(callback.transcript()).isNotNull();
            assertThat(callback.transcript().transcript()).isEqualTo("甲板前方目标接近");
            assertThat(whisperClient.transcribeCalled).isTrue();
            assertThat(llmChatService.handleChatCalled).isFalse();
        } finally {
            executor.shutdownNow();
            llmChatService.shutdown();
        }
    }

    @Test
    void nullModeReturnsInvalidRequestErrorWithoutTranscription() throws Exception {
        RecordingWhisperClient whisperClient = new RecordingWhisperClient();
        RecordingLlmChatService llmChatService = new RecordingLlmChatService();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            VoiceChatService service = new VoiceChatService(
                    whisperClient,
                    whisperProperties,
                    llmChatService,
                    executor
            );

            LlmVoiceRequest request = new LlmVoiceRequest(
                    "conversation-1",
                    "event-1",
                    "test".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "webm",
                    null,
                    java.util.List.of("target-1")
            );

            CapturingSpeechCallback callback = new CapturingSpeechCallback();
            service.handleVoice(request, callback::captureTranscript, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(callback.errorCode()).isEqualTo(LlmErrorCode.LLM_FAILED);
            assertThat(callback.errorMessage()).isEqualTo("Invalid voice request: mode is required.");
            assertThat(callback.reply()).isNull();
            assertThat(callback.transcript()).isNull();
            assertThat(whisperClient.transcribeCalled).isFalse();
            assertThat(llmChatService.handleChatCalled).isFalse();
        } finally {
            executor.shutdownNow();
            llmChatService.shutdown();
        }
    }

    @Test
    void nullRequestReturnsInvalidRequestErrorWithoutTranscription() throws Exception {
        RecordingWhisperClient whisperClient = new RecordingWhisperClient();
        RecordingLlmChatService llmChatService = new RecordingLlmChatService();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            VoiceChatService service = new VoiceChatService(
                    whisperClient,
                    whisperProperties,
                    llmChatService,
                    executor
            );

            CapturingSpeechCallback callback = new CapturingSpeechCallback();
            service.handleVoice(null, callback::captureTranscript, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(callback.errorCode()).isEqualTo(LlmErrorCode.LLM_FAILED);
            assertThat(callback.errorMessage()).isEqualTo("Invalid voice request: mode is required.");
            assertThat(callback.reply()).isNull();
            assertThat(callback.transcript()).isNull();
            assertThat(whisperClient.transcribeCalled).isFalse();
            assertThat(llmChatService.handleChatCalled).isFalse();
        } finally {
            executor.shutdownNow();
            llmChatService.shutdown();
        }
    }

    private static final class CapturingSpeechCallback {
        private VoiceChatService.SpeechTranscriptResult transcript;
        private LlmChatService.ChatReplyResult reply;
        private LlmErrorCode errorCode;
        private String errorMessage;
        private final CountDownLatch latch = new CountDownLatch(1);

        void captureTranscript(VoiceChatService.SpeechTranscriptResult transcript) {
            this.transcript = transcript;
            latch.countDown();
        }

        void captureReply(LlmChatService.ChatReplyResult reply) {
            this.reply = reply;
            latch.countDown();
        }

        void captureError(LlmErrorCode errorCode, String errorMessage) {
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            latch.countDown();
        }

        void await() throws InterruptedException {
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        }

        VoiceChatService.SpeechTranscriptResult transcript() {
            return transcript;
        }

        LlmChatService.ChatReplyResult reply() {
            return reply;
        }

        LlmErrorCode errorCode() {
            return errorCode;
        }

        String errorMessage() {
            return errorMessage;
        }
    }

    private static final class RecordingWhisperClient implements WhisperClient {
        private boolean transcribeCalled;
        private WhisperResponse response;

        @Override
        public WhisperResponse transcribe(byte[] audioData, String audioFormat, String language) {
            this.transcribeCalled = true;
            return response;
        }
    }

    private static final class RecordingLlmChatService extends LlmChatService {
        private boolean handleChatCalled;
        private final ExecutorService executor;

        RecordingLlmChatService() {
            this(Executors.newSingleThreadExecutor());
        }

        private RecordingLlmChatService(ExecutorService executor) {
            super(
                    null,
                    new LlmProperties(),
                    new com.whut.map.map_service.llm.prompt.PromptTemplateService(),
                    new RiskContextHolder(),
                    new RiskContextFormatter(new LlmProperties()),
                    new ExplanationCache(),
                    new ConversationMemory(new LlmProperties()),
                    executor,
                    null, null, null
            );
            this.executor = executor;
        }

        @Override
        public void handleChat(
                LlmChatRequest request,
                java.util.function.Consumer<ChatReplyResult> onSuccess,
                java.util.function.BiConsumer<LlmErrorCode, String> onError
        ) {
            this.handleChatCalled = true;
        }

        void shutdown() {
            executor.shutdownNow();
        }
    }
}
