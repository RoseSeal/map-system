package com.whut.map.map_service.llm.service;

import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.llm.client.LlmClient;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;
import com.whut.map.map_service.llm.dto.LlmExplanation;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.llm.prompt.PromptScene;
import com.whut.map.map_service.llm.prompt.PromptTemplateService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LlmExplanationServiceTest {

    private final PromptTemplateService promptTemplateService = new PromptTemplateService();

    @Test
    void explanationRequestsIncludePromptTemplateAndRiskContext() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L, "gemini");
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.response = "risk reply";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmExplanationService service = new LlmExplanationService(properties, llmClient, promptTemplateService, executor);
            LlmRiskOwnShipContext ownShip = LlmRiskOwnShipContext.builder()
                    .id("own-1")
                    .longitude(120.1234)
                    .latitude(30.5678)
                    .sog(12.3)
                    .cog(87.6)
                    .heading(35.0)
                    .build();
            LlmRiskTargetContext target = LlmRiskTargetContext.builder()
                    .targetId("target-9")
                    .longitude(121.2345)
                    .latitude(31.6789)
                    .speedKn(10.4)
                    .courseDeg(270.1)
                    .riskLevel(RiskLevel.WARNING)
                    .currentDistanceNm(0.80)
                    .relativeBearingDeg(45.0)
                    .dcpaNm(0.42)
                    .tcpaSec(240)
                    .approaching(true)
                    .ruleExplanation("DCPA and TCPA exceed warning thresholds")
                    .build();
            CapturingExplanationCallback callback = new CapturingExplanationCallback();
            service.generateTargetExplanationsAsync(
                    ownShip,
                    List.of(target),
                    callback::captureSuccess,
                    callback::captureError
            );

            callback.await();
            List<LlmChatMessage> messages = llmClient.lastMessages;
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0)).isEqualTo(new LlmChatMessage(
                    ChatRole.SYSTEM,
                    promptTemplateService.getSystemPrompt(PromptScene.RISK_EXPLANATION)
            ));
            assertThat(messages.get(1).role()).isEqualTo(ChatRole.USER);
            assertThat(messages.get(1).content())
                    .contains("【本船】")
                    .contains("ID: own-1")
                    .contains("船首向: 35.0°")
                    .contains("【目标船】")
                    .contains("ID: target-9")
                    .contains("航向: 270.1°")
                    .contains("风险等级: WARNING")
                    .contains("现距: 0.80 海里")
                    .contains("相对方位: 右舷前方 (45°)")
                    .contains("DCPA: 0.42 海里")
                    .contains("TCPA: 240 秒")
                    .contains("接近中: 是")
                    .contains("规则说明: DCPA and TCPA exceed warning thresholds");
            assertThat(callback.success()).isNotNull();
            assertThat(callback.success().getProvider()).isEqualTo("gemini");
            assertThat(callback.success().getText()).isEqualTo("risk reply");
            assertThat(callback.errorCode()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    private LlmProperties buildProperties(boolean enabled, long timeoutMs, String provider) {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(enabled);
        properties.setTimeoutMs(timeoutMs);
        properties.setProvider(provider);
        return properties;
    }

    private static final class CapturingExplanationCallback {
        private LlmExplanation success;
        private LlmErrorCode errorCode;
        private String errorMessage;
        private final CountDownLatch latch = new CountDownLatch(1);

        void captureSuccess(LlmExplanation explanation) {
            this.success = explanation;
            latch.countDown();
        }

        void captureError(LlmRiskTargetContext target, LlmExplanationService.LlmExplanationError error) {
            this.errorCode = error.errorCode();
            this.errorMessage = error.errorMessage();
            latch.countDown();
        }

        void await() throws InterruptedException {
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        }

        LlmExplanation success() {
            return success;
        }

        LlmErrorCode errorCode() {
            return errorCode;
        }

        String errorMessage() {
            return errorMessage;
        }
    }

    private static final class StubLlmClient implements LlmClient {
        private List<LlmChatMessage> lastMessages;
        private String response;

        @Override
        public String chat(List<LlmChatMessage> messages) {
            this.lastMessages = messages;
            return response;
        }
    }
}
