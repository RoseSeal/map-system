import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  selectAiCenterOpenRequestVersion,
  selectChatInput,
  selectChatMessages,
  selectDroppedTargetNotices,
  selectEditingDraft,
  selectEditingMessageEventId,
  selectEditingSubmitError,
  selectEditingSubmitEventId,
  selectExplanationsByTargetId,
  selectIsChatSending,
  selectSelectedTargetIds,
  selectSpeechEnabled,
  selectSpeechSupported,
  selectTargets,
  useAiCenterStore,
  useRiskStore,
} from '../../store';
import { useThemeStore } from '../../store/useThemeStore';
import type { AiCenterChatMessage } from '../../types/aiCenter';
import type { ExplanationPayload, RiskLevel, RiskTarget } from '../../types/schema';
import { translateEncounterType } from '../../utils/riskDisplay';
import { useVoiceCapture } from '../../hooks/useVoiceCapture';
import { speechService } from '../../services/speechService';
import { ChatComposer } from './ChatComposer';
import { ChatMessageList } from './ChatMessageList';
import { CpaArc } from './CpaArc';

const PANEL_WIDTH = 420;

const riskColors: Record<RiskLevel, string> = {
  SAFE: 'oklch(0.76 0.11 158)',
  CAUTION: 'oklch(0.82 0.12 85)',
  WARNING: 'oklch(0.72 0.15 55)',
  ALARM: 'oklch(0.66 0.18 22)',
};

const riskLabels: Record<RiskLevel, string> = {
  SAFE: '安全',
  CAUTION: '注意',
  WARNING: '警告',
  ALARM: '警报',
};

function shouldShowRiskCard(level: RiskLevel) {
  return level === 'CAUTION' || level === 'WARNING' || level === 'ALARM';
}

function getRiskPriority(level: RiskLevel) {
  return level === 'ALARM' ? 3 : level === 'WARNING' ? 2 : level === 'CAUTION' ? 1 : 0;
}

export function RiskExplanationPanel() {
  const targets = useRiskStore(selectTargets);
  const explanationsByTargetId = useRiskStore(selectExplanationsByTargetId);
  const selectedTargetIds = useRiskStore(selectSelectedTargetIds);
  const droppedTargetNotices = useRiskStore(selectDroppedTargetNotices);
  const lastSseError = useRiskStore((state) => state.lastError);
  const selectTarget = useRiskStore((state) => state.selectTarget);
  const deselectTarget = useRiskStore((state) => state.deselectTarget);
  const clearDroppedTargetNotices = useRiskStore((state) => state.clearDroppedTargetNotices);
  const clearRiskError = useRiskStore((state) => state.clearRiskError);

  const aiCenterOpenRequestVersion = useAiCenterStore(selectAiCenterOpenRequestVersion);
  const chatMessages = useAiCenterStore(selectChatMessages);
  const chatInput = useAiCenterStore(selectChatInput);
  const editingMessageEventId = useAiCenterStore(selectEditingMessageEventId);
  const editingDraft = useAiCenterStore(selectEditingDraft);
  const editingSubmitEventId = useAiCenterStore(selectEditingSubmitEventId);
  const editingSubmitError = useAiCenterStore(selectEditingSubmitError);
  const isChatSending = useAiCenterStore(selectIsChatSending);
  const speechEnabled = useAiCenterStore(selectSpeechEnabled);

  const speechSupported = useAiCenterStore(selectSpeechSupported);

  const setChatInput = useAiCenterStore((state) => state.setChatInput);
  const sendTextMessage = useAiCenterStore((state) => state.sendTextMessage);
  const startEditingLastUserMessage = useAiCenterStore((state) => state.startEditingLastUserMessage);
  const updateEditingDraft = useAiCenterStore((state) => state.updateEditingDraft);
  const confirmEditingLastUserMessage = useAiCenterStore((state) => state.confirmEditingLastUserMessage);
  const cancelEditingLastUserMessage = useAiCenterStore((state) => state.cancelEditingLastUserMessage);
  const clearEditingSubmitError = useAiCenterStore((state) => state.clearEditingSubmitError);
  const resetConversation = useAiCenterStore((state) => state.resetConversation);
  const setSpeechEnabled = useAiCenterStore((state) => state.setSpeechEnabled);
  const setSpeechUnlocked = useAiCenterStore((state) => state.setSpeechUnlocked);

  const { isDarkMode, toggleTheme } = useThemeStore();
  const {
    activeVoiceMode,
    cancelVoiceCapture,
    handleStartVoiceRecording,
    handleStopVoiceRecording,
    voiceCaptureError,
    voiceCaptureState,
    voiceCaptureSupported,
  } = useVoiceCapture();

  const [isOpen, setIsOpen] = useState(false);
  const [topSectionHeight, setTopSectionHeight] = useState(55);
  const [chatSendError, setChatSendError] = useState<string | null>(null);
  const [visibleSseError, setVisibleSseError] = useState<string | null>(null);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);

  const isDragging = useRef(false);

  useEffect(() => {
    if (!lastSseError) {
      return;
    }

    setVisibleSseError(lastSseError.error_message);
    const timerId = setTimeout(() => {
      setVisibleSseError(null);
      clearRiskError();
    }, 4000);

    return () => clearTimeout(timerId);
  }, [clearRiskError, lastSseError]);

  useEffect(() => {
    if (aiCenterOpenRequestVersion > 0) {
      setIsOpen(true);
    }
  }, [aiCenterOpenRequestVersion]);

  useEffect(() => {
    if (voiceCaptureState === 'recording' || voiceCaptureState === 'transcribing') {
      setIsOpen(true);
    }
  }, [voiceCaptureState]);

  const handleMouseMove = useCallback((event: MouseEvent) => {
    if (!isDragging.current) {
      return;
    }

    const panelRect = document.getElementById('ai-center-panel')?.getBoundingClientRect();
    if (!panelRect) {
      return;
    }

    let nextPercentage = ((event.clientY - panelRect.top) / panelRect.height) * 100;
    nextPercentage = Math.max(20, Math.min(80, nextPercentage));
    setTopSectionHeight(nextPercentage);
  }, []);

  const handleMouseUp = useCallback(() => {
    isDragging.current = false;
    document.removeEventListener('mousemove', handleMouseMove);
    document.removeEventListener('mouseup', handleMouseUp);
  }, [handleMouseMove]);

  useEffect(() => () => {
    document.removeEventListener('mousemove', handleMouseMove);
    document.removeEventListener('mouseup', handleMouseUp);
  }, [handleMouseMove, handleMouseUp]);

  const handleDividerMouseDown = () => {
    isDragging.current = true;
    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);
  };

  const sortedExplainedTargets = useMemo(() => (
    targets
      .map((target) => {
        const explanation = explanationsByTargetId[target.id];
        if (!explanation || !shouldShowRiskCard(explanation.risk_level)) {
          return null;
        }

        return { target, explanation };
      })
      .filter((item): item is { target: RiskTarget; explanation: ExplanationPayload } => Boolean(item))
      .sort((left, right) => getRiskPriority(right.explanation.risk_level) - getRiskPriority(left.explanation.risk_level))
  ), [explanationsByTargetId, targets]);

  const selectedTargetsForChip = useMemo(() => (
    selectedTargetIds
      .map((id) => {
        const target = targets.find((item) => item.id === id);
        return target ? { id: target.id, riskLevel: target.risk_assessment.risk_level } : null;
      })
      .filter((item): item is { id: string; riskLevel: RiskLevel } => item !== null)
  ), [selectedTargetIds, targets]);

  const handleSendChat = () => {
    const sent = sendTextMessage(chatInput);
    if (sent) {
      return;
    }

    setChatSendError('发送失败：连接已断开');
    setTimeout(() => setChatSendError(null), 3000);
  };

  const handleRetry = (message: AiCenterChatMessage) => {
    if (message.role !== 'user' || message.request_type === 'SPEECH') {
      return;
    }

    sendTextMessage(message.content);
  };

  const handleResetConversation = () => {
    cancelVoiceCapture();
    resetConversation();
  };

  const handleSpeechToggle = () => {
    if (!speechSupported) {
      return;
    }

    if (speechEnabled) {
      setSpeechEnabled(false);
      speechService.stop();
      return;
    }

    const unlocked = speechService.unlock();
    setSpeechUnlocked(unlocked);
    setSpeechEnabled(unlocked);
  };

  const glassClass = isDarkMode ? 'glass-vision-dark' : 'glass-vision';

  return (
    <div className="pointer-events-none fixed right-0 top-0 bottom-0 z-50">
      <button
        type="button"
        onClick={() => setIsOpen(true)}
        className="pointer-events-auto absolute flex justify-end pr-1"
        style={{
          right: 0,
          top: '50%',
          transform: 'translateY(-50%)',
          width: 36,
          opacity: isOpen ? 0 : 1,
          pointerEvents: isOpen ? 'none' : 'auto',
          transition: 'opacity 0.3s ease',
        }}
        aria-label="打开 AI 助手"
      >
        <div
          className={glassClass}
          style={{
            borderRight: 'none',
            borderRadius: '16px 0 0 16px',
            padding: '20px 8px',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 10,
          }}
        >
          <span style={{ position: 'relative', width: 10, height: 10, display: 'inline-block' }}>
            <span
              className="anim-soft-pulse"
              style={{
                position: 'absolute',
                inset: 0,
                borderRadius: 999,
                background: 'var(--accent)',
                opacity: 0.5,
              }}
            />
            <span
              style={{
                position: 'absolute',
                inset: 0,
                borderRadius: 999,
                background: 'var(--accent)',
              }}
            />
          </span>

          {sortedExplainedTargets.length > 0 && (
            <span
              style={{
                width: 18,
                height: 18,
                borderRadius: 999,
                background: 'var(--risk-warning)',
                color: 'white',
                fontSize: 9,
                fontWeight: 600,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontFamily: 'JetBrains Mono, monospace',
              }}
            >
              {sortedExplainedTargets.length}
            </span>
          )}

          <span
            style={{
              writingMode: 'vertical-rl',
              fontSize: 9,
              fontWeight: 600,
              letterSpacing: '0.28em',
              textTransform: 'uppercase',
              color: 'var(--ink-700)',
            }}
          >
            AI
          </span>

          <svg
            width="12"
            height="12"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={2}
            style={{ color: 'var(--ink-500)' }}
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
          </svg>
        </div>
      </button>

      <div
        id="ai-center-panel"
        className={`${glassClass} pointer-events-auto flex flex-col overflow-hidden absolute top-0 bottom-0 right-0`}
        style={{
          width: PANEL_WIDTH,
          borderRadius: '24px 0 0 24px',
          borderRight: 'none',
          transition: 'transform 0.5s cubic-bezier(0.16,1,0.3,1), opacity 0.4s',
          transform: isOpen ? 'translateX(0)' : `translateX(${PANEL_WIDTH}px)`,
          opacity: isOpen ? 1 : 0,
        }}
      >
        <div
          className="flex items-center justify-between px-5 pb-4 pt-5"
          style={{ borderBottom: '0.5px solid color-mix(in oklch, var(--ink-500) 12%, transparent)' }}
        >
          <div>
            <div className="flex items-center gap-2.5">
              <div
                style={{
                  width: 28,
                  height: 28,
                  borderRadius: 999,
                  background: 'color-mix(in oklch, var(--accent) 16%, transparent)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <svg
                  viewBox="0 0 16 16"
                  width="14"
                  height="14"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  style={{ color: 'var(--accent)' }}
                >
                  <path
                    d="M8 2L10 6L14 6.5L11 9.5L11.8 13.5L8 11.5L4.2 13.5L5 9.5L2 6.5L6 6Z"
                    strokeLinejoin="round"
                  />
                </svg>
              </div>
              <span className="text-[15px] font-semibold tracking-tight" style={{ color: 'var(--ink-900)' }}>
                AI 航海助手
              </span>
              <span
                className="rounded-full px-1.5 py-0.5 font-mono text-[8px]"
                style={{
                  background: 'color-mix(in oklch, var(--accent) 12%, transparent)',
                  color: 'var(--accent)',
                }}
              >
                LLM
              </span>
            </div>
            <div className="mt-1 pl-[38px] text-[11px]" style={{ color: 'var(--ink-500)' }}>
              {chatMessages.length} 条消息
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setIsSettingsOpen((open) => !open)}
              style={{
                width: 28,
                height: 28,
                borderRadius: 999,
                background: isSettingsOpen
                  ? 'color-mix(in oklch, var(--accent) 14%, transparent)'
                  : 'color-mix(in oklch, var(--ink-500) 10%, transparent)',
                border: 'none',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <svg
                width="14"
                height="14"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
                strokeWidth={2}
                style={{ color: isSettingsOpen ? 'var(--accent)' : 'var(--ink-500)' }}
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
                />
                <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
              </svg>
            </button>

            <button
              type="button"
              onClick={() => setIsOpen(false)}
              style={{
                width: 28,
                height: 28,
                borderRadius: 999,
                background: 'color-mix(in oklch, var(--ink-500) 10%, transparent)',
                border: 'none',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <svg
                width="12"
                height="12"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
                strokeWidth={2}
                style={{ color: 'var(--ink-500)' }}
              >
                <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
              </svg>
            </button>
          </div>
        </div>

        <div
          style={{
            overflow: 'hidden',
            maxHeight: isSettingsOpen ? 140 : 0,
            opacity: isSettingsOpen ? 1 : 0,
            transition: 'max-height 0.3s cubic-bezier(0.16,1,0.3,1), opacity 0.2s',
            borderBottom: '0.5px solid color-mix(in oklch, var(--ink-500) 12%, transparent)',
            background: 'color-mix(in oklch, var(--ink-500) 3%, transparent)',
          }}
        >
          <div className="space-y-3 p-4">
            <div className="flex items-center justify-between">
              <div>
                <div className="text-[10px] font-semibold" style={{ color: 'var(--ink-700)' }}>
                  显示模式
                </div>
                <div className="text-[9px]" style={{ color: 'var(--ink-500)' }}>
                  切换深色 / 浅色主题
                </div>
              </div>
              <button
                type="button"
                onClick={toggleTheme}
                className="rounded-lg px-3 py-1 text-[10px] font-medium"
                style={{
                  border: '0.5px solid color-mix(in oklch, var(--ink-500) 20%, transparent)',
                  background: 'color-mix(in oklch, var(--ink-500) 8%, transparent)',
                  color: 'var(--ink-700)',
                  cursor: 'pointer',
                }}
              >
                {isDarkMode ? '深色模式' : '浅色模式'}
              </button>
            </div>

            <div className="flex items-center justify-between">
              <div>
                <div className="text-[10px] font-semibold" style={{ color: 'var(--ink-700)' }}>
                  语音播报
                </div>
                <div className="text-[9px]" style={{ color: 'var(--ink-500)' }}>
                  {speechSupported ? '自动播报 AI 评估' : '浏览器不支持'}
                </div>
              </div>
              <button
                type="button"
                onClick={handleSpeechToggle}
                disabled={!speechSupported}
                className="rounded-lg px-3 py-1 text-[10px] font-medium"
                style={{
                  border: `0.5px solid ${speechEnabled
                    ? 'color-mix(in oklch, var(--accent) 40%, transparent)'
                    : 'color-mix(in oklch, var(--ink-500) 20%, transparent)'}`,
                  background: speechEnabled
                    ? 'color-mix(in oklch, var(--accent) 12%, transparent)'
                    : 'color-mix(in oklch, var(--ink-500) 8%, transparent)',
                  color: speechEnabled ? 'var(--accent)' : 'var(--ink-500)',
                  cursor: speechSupported ? 'pointer' : 'not-allowed',
                }}
              >
                {speechEnabled ? '已开启' : '已关闭'}
              </button>
            </div>
          </div>
        </div>

        <div className="flex min-h-0 flex-1 flex-col">
          <section
            style={{ height: `${topSectionHeight}%` }}
            className="flex min-h-0 flex-col"
          >
            <div
              className="flex items-center justify-between px-5 py-2.5"
              style={{ borderBottom: '0.5px solid color-mix(in oklch, var(--ink-500) 8%, transparent)' }}
            >
              <span className="text-[11px] font-semibold" style={{ color: 'var(--ink-900)' }}>
                风险评估
              </span>
              <span className="tnum font-mono text-[10px]" style={{ color: 'var(--ink-500)' }}>
                {sortedExplainedTargets.length} 目标
              </span>
            </div>

            {visibleSseError && (
              <div
                className="anim-soft-pulse px-5 py-1.5 text-[10px] font-medium"
                style={{ color: 'var(--risk-alarm)' }}
              >
                服务连接异常：{visibleSseError}
              </div>
            )}

            <div className="scrollbar-apple flex-1 min-h-0 space-y-2.5 overflow-y-auto p-4">
              {sortedExplainedTargets.length === 0 ? (
                <div className="flex h-full flex-col items-center justify-center px-6 text-center">
                  <div
                    style={{
                      width: 44,
                      height: 44,
                      borderRadius: 999,
                      marginBottom: 12,
                      background: 'color-mix(in oklch, var(--risk-safe) 12%, transparent)',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                    }}
                  >
                    <svg
                      width="20"
                      height="20"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth="1.8"
                      style={{ color: 'var(--risk-safe)' }}
                    >
                      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                    </svg>
                  </div>
                  <p className="text-[12px] font-medium" style={{ color: 'var(--ink-700)' }}>
                    航区平稳
                  </p>
                  <p className="mt-1 text-[11px]" style={{ color: 'var(--ink-500)' }}>
                    当前无需 AI 介入
                  </p>
                </div>
              ) : (
                sortedExplainedTargets.map(({ target, explanation }) => {
                  const color = riskColors[explanation.risk_level];
                  const isSelected = selectedTargetIds.includes(target.id);
                  const encounterLabel = translateEncounterType(target.risk_assessment.encounter_type);

                  return (
                    <div
                      key={explanation.event_id}
                      onClick={() => selectTarget(target.id)}
                      className="cursor-pointer overflow-hidden rounded-2xl transition-all duration-200"
                      style={{
                        background: isSelected
                          ? `color-mix(in oklch, ${color} 8%, ${isDarkMode ? 'rgba(15,23,42,0.6)' : 'rgba(255,255,255,0.9)'})`
                          : isDarkMode
                            ? 'rgba(255,255,255,0.04)'
                            : 'rgba(255,255,255,0.6)',
                        border: `0.5px solid color-mix(in oklch, ${color} ${isSelected ? 35 : 20}%, transparent)`,
                        boxShadow: isSelected
                          ? `0 4px 16px -6px color-mix(in oklch, ${color} 28%, transparent)`
                          : 'none',
                      }}
                    >
                      <div
                        className="flex items-center gap-3 px-4 py-2.5"
                        style={{ borderBottom: `0.5px solid color-mix(in oklch, ${color} 18%, transparent)` }}
                      >
                        <CpaArc
                          tcpa_sec={target.risk_assessment.cpa_metrics.tcpa_sec}
                          dcpa_nm={target.risk_assessment.cpa_metrics.dcpa_nm}
                          riskLevel={explanation.risk_level}
                          size={40}
                        />

                        <div className="min-w-0 flex-1">
                          <div className="mb-0.5 flex items-center gap-2">
                            <span
                              className="tnum font-mono text-[12px] font-semibold"
                              style={{ color: 'var(--ink-900)' }}
                            >
                              {target.id}
                            </span>
                            {encounterLabel && (
                              <span
                                className="rounded px-1.5 py-0.5 text-[8px] font-medium"
                                style={{
                                  background: 'color-mix(in oklch, var(--ink-500) 10%, transparent)',
                                  color: 'var(--ink-500)',
                                }}
                              >
                                {encounterLabel}
                              </span>
                            )}
                            <span
                              className="ml-auto rounded-full px-1.5 py-0.5 text-[9px] font-semibold"
                              style={{
                                color,
                                background: `color-mix(in oklch, ${color} 15%, transparent)`,
                              }}
                            >
                              {riskLabels[explanation.risk_level]}
                            </span>
                          </div>
                          <div className="text-[10px]" style={{ color: 'var(--ink-500)' }}>
                            {explanation.provider} · {explanation.timestamp}
                          </div>
                        </div>
                      </div>

                      <p
                        className="px-4 py-3 text-[12.5px] leading-relaxed"
                        style={{ color: 'var(--ink-700)' }}
                      >
                        {explanation.text}
                      </p>
                    </div>
                  );
                })
              )}
            </div>
          </section>

          <div
            className="h-1 cursor-row-resize transition-colors"
            style={{ background: 'color-mix(in oklch, var(--ink-500) 8%, transparent)' }}
            onMouseDown={handleDividerMouseDown}
            onMouseEnter={(event) => {
              event.currentTarget.style.background = 'color-mix(in oklch, var(--accent) 40%, transparent)';
            }}
            onMouseLeave={(event) => {
              event.currentTarget.style.background = 'color-mix(in oklch, var(--ink-500) 8%, transparent)';
            }}
          />

          <section
            style={{ height: `${100 - topSectionHeight}%` }}
            className="flex min-h-0 flex-col"
          >
            <div
              className="flex items-center justify-between px-5 py-2.5"
              style={{ borderBottom: '0.5px solid color-mix(in oklch, var(--ink-500) 8%, transparent)' }}
            >
              <span className="text-[11px] font-semibold" style={{ color: 'var(--ink-900)' }}>
                智能决策助理
              </span>
              <button
                type="button"
                onClick={handleResetConversation}
                className="rounded-lg px-2 py-0.5 text-[9px]"
                style={{
                  border: '0.5px solid color-mix(in oklch, var(--ink-500) 20%, transparent)',
                  color: 'var(--ink-500)',
                  cursor: 'pointer',
                  background: 'transparent',
                }}
              >
                重置会话
              </button>
            </div>

            <ChatMessageList
              messages={chatMessages}
              onRetry={handleRetry}
              editingMessageEventId={editingMessageEventId}
              editingDraft={editingDraft}
              editingSubmitPending={Boolean(editingSubmitEventId)}
              editingSubmitError={editingSubmitError}
              onStartEditingLastUserMessage={startEditingLastUserMessage}
              onUpdateEditingDraft={updateEditingDraft}
              onConfirmEditingLastUserMessage={confirmEditingLastUserMessage}
              onCancelEditingLastUserMessage={cancelEditingLastUserMessage}
              onClearEditingSubmitError={clearEditingSubmitError}
            />

            <ChatComposer
              value={chatInput}
              disabled={isChatSending}
              isSending={isChatSending}
              voiceSupported={voiceCaptureSupported}
              voiceState={voiceCaptureState}
              voiceMode={activeVoiceMode}
              voiceError={voiceCaptureError}
              sendError={chatSendError}
              selectedTargets={selectedTargetsForChip}
              droppedTargetNotices={droppedTargetNotices}
              onDeselectTarget={deselectTarget}
              onClearDroppedNotices={clearDroppedTargetNotices}
              onChange={setChatInput}
              onSend={handleSendChat}
              onStartVoiceRecording={handleStartVoiceRecording}
              onStopVoiceRecording={handleStopVoiceRecording}
              onCancelVoiceRecording={cancelVoiceCapture}
            />
          </section>
        </div>
      </div>
    </div>
  );
}
