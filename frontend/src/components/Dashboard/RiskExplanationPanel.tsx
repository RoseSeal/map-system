import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  selectAiCenterOpenRequestVersion,
  selectActiveAdvisory,
  selectAgentStepsByReplyToEventId,
  selectAssistantMode,
  selectChatCapability,
  selectChatCapabilityState,
  selectChatInput,
  selectChatMessages,
  selectDroppedTargetNotices,
  selectEditingDraft,
  selectEditingMessageEventId,
  selectEditingSubmitError,
  selectEditingSubmitEventId,
  selectEnvironment,
  selectExplanationsByTargetId,
  selectIsChatSending,
  selectRiskConnectionError,
  selectResolvedExplanations,
  selectSelectedTargetIds,
  selectProviderCapabilities,
  selectProviderSelection,
  selectProviderSelectionPending,
  selectProviderSelectionError,
  selectSpeechEnabled,
  selectSpeechSupported,
  selectTargets,
  useAiCenterStore,
  useRiskStore,
} from '../../store';
import { AdvisoryCard } from './AdvisoryCard';
import { useThemeStore } from '../../store/useThemeStore';
import { useMapSettingsStore } from '../../store/useMapSettingsStore';
import {
  CONTOUR_MIN,
  CONTOUR_MAX,
  CONTOUR_STEP,
} from '../../utils/safetyContour';
import type { AiCenterChatMessage } from '../../types/aiCenter';
import type { RiskLevel, RiskTarget, StoredExplanation } from '../../types/schema';
import { translateEncounterType } from '../../utils/riskDisplay';
import { useVoiceCapture } from '../../hooks/useVoiceCapture';
import { speechService } from '../../services/speechService';
import { ChatComposer } from './ChatComposer';
import { ChatMessageList } from './ChatMessageList';
import { CpaArc } from './CpaArc';

const PANEL_WIDTH = 420;
const EXIT_ANIMATION_MS = 220;
const RISK_ERROR_VISIBLE_MS = 4000;

type ExplainedCardData = {
  target: RiskTarget;
  explanation: StoredExplanation;
};

type DisplayedRiskCard = ExplainedCardData & {
  isLeaving: boolean;
  renderKey: string;
};

type VisibleRiskError = {
  kind: 'connection' | 'event';
  message: string;
} | null;

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
  const activeAdvisory = useRiskStore(selectActiveAdvisory);
  const selectedTargetIds = useRiskStore(selectSelectedTargetIds);
  const droppedTargetNotices = useRiskStore(selectDroppedTargetNotices);
  const riskConnectionError = useRiskStore(selectRiskConnectionError);
  const lastRiskError = useRiskStore((state) => state.lastError);
  const selectTarget = useRiskStore((state) => state.selectTarget);
  const deselectTarget = useRiskStore((state) => state.deselectTarget);
  const clearDroppedTargetNotices = useRiskStore((state) => state.clearDroppedTargetNotices);
  const clearRiskError = useRiskStore((state) => state.clearRiskError);
  const resolvedExplanations = useRiskStore(selectResolvedExplanations);

  const aiCenterOpenRequestVersion = useAiCenterStore(selectAiCenterOpenRequestVersion);
  const chatMessages = useAiCenterStore(selectChatMessages);
  const chatInput = useAiCenterStore(selectChatInput);
  const editingMessageEventId = useAiCenterStore(selectEditingMessageEventId);
  const editingDraft = useAiCenterStore(selectEditingDraft);
  const editingSubmitEventId = useAiCenterStore(selectEditingSubmitEventId);
  const editingSubmitError = useAiCenterStore(selectEditingSubmitError);
  const isChatSending = useAiCenterStore(selectIsChatSending);
  const assistantMode = useAiCenterStore(selectAssistantMode);
  const chatCapabilityState = useAiCenterStore(selectChatCapabilityState);
  const chatCapability = useAiCenterStore(selectChatCapability);
  const providerCapabilities = useAiCenterStore(selectProviderCapabilities);
  const providerSelection = useAiCenterStore(selectProviderSelection);
  const providerSelectionPending = useAiCenterStore(selectProviderSelectionPending);
  const providerSelectionError = useAiCenterStore(selectProviderSelectionError);
  const agentStepsByReplyToEventId = useAiCenterStore(selectAgentStepsByReplyToEventId);
  const setAssistantMode = useAiCenterStore((state) => state.setAssistantMode);
  const setProviderSelection = useAiCenterStore((state) => state.setProviderSelection);
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
  const clearExpiredExplanations = useAiCenterStore((state) => state.clearExpiredExplanations);
  const setPendingExplanationRef = useAiCenterStore((state) => state.setPendingExplanationRef);

  const environment = useRiskStore(selectEnvironment);
  const { isDarkMode, toggleTheme } = useThemeStore();
  const {
    safetyContourOverride,
    setSafetyContourOverride,
    followMode,
    setFollowMode,
  } = useMapSettingsStore();

  const liveSafetyContourVal = environment?.safety_contour_val ?? 10;
  const effectiveSafetyContourVal = safetyContourOverride ?? liveSafetyContourVal;

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
  const [visibleRiskError, setVisibleRiskError] = useState<VisibleRiskError>(null);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [leavingIds, setLeavingIds] = useState<Set<string>>(new Set());

  const isDragging = useRef(false);
  const cardDataRef = useRef<Map<string, ExplainedCardData>>(new Map());
  const leavingTimerIdsRef = useRef<Map<string, number>>(new Map());
  const prevCardIdsRef = useRef<Set<string>>(new Set());
  const renderOrderRef = useRef<string[]>([]);

  useEffect(() => {
    const nextError: VisibleRiskError = riskConnectionError
      ? { kind: 'connection', message: riskConnectionError }
      : lastRiskError
        ? { kind: 'event', message: lastRiskError.error_message }
        : null;

    if (!nextError) {
      setVisibleRiskError(null);
      return;
    }

    setVisibleRiskError(nextError);
    const timerId = window.setTimeout(() => {
      setVisibleRiskError(null);
      clearRiskError();
    }, RISK_ERROR_VISIBLE_MS);

    return () => window.clearTimeout(timerId);
  }, [clearRiskError, lastRiskError, riskConnectionError]);

  useEffect(() => {
    if (aiCenterOpenRequestVersion > 0) {
      setIsOpen(true);
    }
  }, [aiCenterOpenRequestVersion]);

  useEffect(() => {
    if (followMode === 'OFF') {
      setFollowMode('SOFT');
    }
  }, [followMode, setFollowMode]);

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
      .filter((item): item is { target: RiskTarget; explanation: StoredExplanation } => Boolean(item))
      .sort((left, right) => getRiskPriority(right.explanation.risk_level) - getRiskPriority(left.explanation.risk_level))
  ), [explanationsByTargetId, targets]);

  const currentCardsById = useMemo(
    () => new Map(sortedExplainedTargets.map((item) => [item.target.id, item])),
    [sortedExplainedTargets],
  );
  const currentCardIds = useMemo(
    () => sortedExplainedTargets.map((item) => item.target.id),
    [sortedExplainedTargets],
  );

  useLayoutEffect(() => {
    sortedExplainedTargets.forEach((item) => {
      cardDataRef.current.set(item.target.id, item);
    });

    const currentIds = new Set(currentCardIds);
    currentIds.forEach((id) => {
      const existingTimerId = leavingTimerIdsRef.current.get(id);
      if (existingTimerId !== undefined) {
        window.clearTimeout(existingTimerId);
        leavingTimerIdsRef.current.delete(id);
      }
    });

    const gone = [...prevCardIdsRef.current].filter((id) => !currentIds.has(id));
    prevCardIdsRef.current = currentIds;

    setLeavingIds((prev) => {
      const next = new Set([...prev].filter((id) => !currentIds.has(id)));
      gone.forEach((id) => next.add(id));
      return next;
    });

    gone.forEach((id) => {
      const existingTimerId = leavingTimerIdsRef.current.get(id);
      if (existingTimerId !== undefined) {
        window.clearTimeout(existingTimerId);
      }

      const timerId = window.setTimeout(() => {
        setLeavingIds((prev) => {
          if (!prev.has(id)) {
            return prev;
          }

          const next = new Set(prev);
          next.delete(id);
          return next;
        });
        leavingTimerIdsRef.current.delete(id);
        cardDataRef.current.delete(id);
      }, EXIT_ANIMATION_MS);

      leavingTimerIdsRef.current.set(id, timerId);
    });
  }, [currentCardIds, sortedExplainedTargets]);

  useEffect(() => () => {
    leavingTimerIdsRef.current.forEach((timerId) => {
      window.clearTimeout(timerId);
    });
    leavingTimerIdsRef.current.clear();
  }, []);

  const displayedCards = useMemo<DisplayedRiskCard[]>(() => {
    const visibleLeavingIds = [...leavingIds].filter(
      (id) => !currentCardsById.has(id) && cardDataRef.current.has(id),
    );
    const previousOrder = renderOrderRef.current;
    const previousOrderSet = new Set(previousOrder);
    const orderedIds = [
      ...previousOrder.filter((id) => currentCardsById.has(id) || visibleLeavingIds.includes(id)),
      ...currentCardIds.filter((id) => !previousOrderSet.has(id)),
      ...visibleLeavingIds.filter((id) => !previousOrderSet.has(id)),
    ];

    return orderedIds
      .map((id) => {
        const currentCard = currentCardsById.get(id);
        if (currentCard) {
          return {
            ...currentCard,
            isLeaving: false,
            renderKey: id,
          };
        }

        const leavingCard = cardDataRef.current.get(id);
        if (!leavingCard) {
          return null;
        }

        return {
          ...leavingCard,
          isLeaving: true,
          renderKey: `${id}::leaving`,
        };
      })
      .filter((item): item is DisplayedRiskCard => item !== null);
  }, [currentCardIds, currentCardsById, leavingIds]);

  useLayoutEffect(() => {
    renderOrderRef.current = displayedCards.map((card) => card.target.id);
  }, [displayedCards]);

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

  const handleSpeechEnabledChange = (nextEnabled: boolean) => {
    if (!speechSupported || speechEnabled === nextEnabled) {
      return;
    }

    handleSpeechToggle();
  };

  const providerSelectionMutable = chatCapability?.provider_selection_mutable ?? true;

  const explanationProviderOptions = useMemo(
    () => providerCapabilities.filter((provider) => provider.supported_tasks.includes('explanation')),
    [providerCapabilities],
  );

  const chatProviderOptions = useMemo(
    () => providerCapabilities.filter((provider) => provider.supported_tasks.includes('chat')),
    [providerCapabilities],
  );

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
            maxHeight: isSettingsOpen ? 620 : 0,
            opacity: isSettingsOpen ? 1 : 0,
            transition: 'max-height 0.4s cubic-bezier(0.16,1,0.3,1), opacity 0.2s',
            borderBottom: '0.5px solid color-mix(in oklch, var(--ink-500) 12%, transparent)',
            background: 'color-mix(in oklch, var(--ink-500) 3%, transparent)',
          }}
        >
          <div className="space-y-4 p-5">
            <div className="flex items-center">
              <div className="w-[140px] flex-shrink-0">
                <div className="text-[11px] font-semibold" style={{ color: 'var(--ink-700)' }}>
                  显示模式
                </div>
                <div className="text-[10px] leading-tight" style={{ color: 'var(--ink-500)' }}>
                  切换深浅主题
                </div>
              </div>
              <div className="flex flex-1 justify-end">
                <div
                  className="inline-flex w-[146px] items-center gap-0.5 rounded-lg p-0.5"
                  style={{
                    background: 'color-mix(in oklch, var(--ink-500) 8%, transparent)',
                    border: '0.5px solid color-mix(in oklch, var(--ink-500) 10%, transparent)',
                  }}
                >
                  {([
                    { key: 'light', label: '浅色模式', active: !isDarkMode },
                    { key: 'dark', label: '深色模式', active: isDarkMode }
                  ]).map((item) => (
                    <button
                      key={item.key}
                      type="button"
                      onClick={() => {
                        if (!item.active) {
                          toggleTheme();
                        }
                      }}
                      className="flex-1 px-3 py-1 text-[10px] font-medium transition-all duration-200"
                      style={{
                        borderRadius: '6px',
                        border: 'none',
                        background: item.active
                          ? (isDarkMode ? 'rgba(255,255,255,0.12)' : 'white')
                          : 'transparent',
                        boxShadow: item.active ? '0 1px 3px rgba(0,0,0,0.1)' : 'none',
                        color: item.active ? 'var(--accent)' : 'var(--ink-500)',
                        cursor: 'pointer',
                      }}
                    >
                      {item.label}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            <div className="flex items-center">
              <div className="w-[140px] flex-shrink-0">
                <div className="text-[11px] font-semibold" style={{ color: 'var(--ink-700)' }}>
                  语音播报
                </div>
                <div className="text-[10px] leading-tight" style={{ color: 'var(--ink-500)' }}>
                  {speechSupported ? '自动播报 AI 评估' : '浏览器不支持'}
                </div>
              </div>
              <div className="flex flex-1 justify-end">
                <div
                  className="inline-flex w-[146px] items-center gap-0.5 rounded-lg p-0.5"
                  style={{
                    background: 'color-mix(in oklch, var(--ink-500) 8%, transparent)',
                    border: '0.5px solid color-mix(in oklch, var(--ink-500) 10%, transparent)',
                    opacity: speechSupported ? 1 : 0.5,
                  }}
                >
                  {([
                    { val: true, label: '已开启' },
                    { val: false, label: '已关闭' }
                  ]).map((item) => {
                    const isActive = speechEnabled === item.val;
                    return (
                      <button
                        key={String(item.val)}
                        type="button"
                        disabled={!speechSupported}
                        onClick={() => handleSpeechEnabledChange(item.val)}
                        className="flex-1 px-3 py-1 text-[10px] font-medium transition-all duration-200"
                        style={{
                          borderRadius: '6px',
                          border: 'none',
                          background: isActive
                            ? (isDarkMode ? 'rgba(255,255,255,0.12)' : 'white')
                            : 'transparent',
                          boxShadow: isActive ? '0 1px 3px rgba(0,0,0,0.1)' : 'none',
                          color: isActive ? 'var(--accent)' : 'var(--ink-500)',
                          cursor: speechSupported ? 'pointer' : 'not-allowed',
                        }}
                      >
                        {item.label}
                      </button>
                    );
                  })}
                </div>
              </div>
            </div>

            <div className="flex items-center">
              <div className="w-[140px] flex-shrink-0">
                <div className="text-[11px] font-semibold" style={{ color: 'var(--ink-700)' }}>
                  Chat 模型
                </div>
                <div className="text-[10px] leading-tight" style={{ color: 'var(--ink-500)' }}>
                  Chat / Agent 共用 provider
                </div>
              </div>
              <div className="flex flex-1 justify-end">
                <div
                  className="inline-flex w-[146px] items-center gap-0.5 rounded-lg p-0.5"
                  style={{
                    background: 'color-mix(in oklch, var(--ink-500) 8%, transparent)',
                    border: '0.5px solid color-mix(in oklch, var(--ink-500) 10%, transparent)',
                    opacity: (!providerSelection || !providerSelectionMutable) ? 0.5 : 1,
                    transition: 'opacity 0.2s',
                  }}
                >
                  {!providerSelection
                    ? (
                      <span className="flex-1 py-1 text-center text-[10px]" style={{ color: 'var(--ink-400)' }}>
                        等待中...
                      </span>
                    )
                    : chatProviderOptions.map((provider) => {
                      const isActive = providerSelection.chat_provider === provider.provider;
                      const isUnavailable = !provider.available;
                      const isInteractive = !isUnavailable && !providerSelectionPending && providerSelectionMutable;
                      return (
                        <button
                          key={provider.provider}
                          type="button"
                          disabled={isUnavailable || providerSelectionPending || !providerSelectionMutable}
                          title={isUnavailable ? (provider.disabled_reason ?? '不可用') : undefined}
                          onClick={() => {
                            if (!isActive && isInteractive) {
                              setProviderSelection({ chat_provider: provider.provider as 'gemini' | 'zhipu' });
                            }
                          }}
                          className="flex-1 px-3 py-1 text-[10px] font-medium transition-all duration-200"
                          style={{
                            borderRadius: '6px',
                            border: 'none',
                            background: isActive
                              ? (isDarkMode ? 'rgba(255,255,255,0.12)' : 'white')
                              : 'transparent',
                            boxShadow: isActive ? '0 1px 3px rgba(0,0,0,0.1)' : 'none',
                            color: isActive ? 'var(--accent)' : (isUnavailable ? 'var(--ink-400)' : 'var(--ink-500)'),
                            cursor: isInteractive ? (isActive ? 'default' : 'pointer') : 'not-allowed',
                            opacity: isUnavailable ? 0.4 : 1,
                          }}
                        >
                          {provider.display_name}
                        </button>
                      );
                    })
                  }
                </div>
              </div>
            </div>

            <div className="flex items-center">
              <div className="w-[140px] flex-shrink-0">
                <div className="text-[11px] font-semibold" style={{ color: 'var(--ink-700)' }}>
                  风险解释模型
                </div>
                <div className="text-[10px] leading-tight" style={{ color: 'var(--ink-500)' }}>
                  新 explanation 生成 provider
                </div>
              </div>
              <div className="flex flex-1 justify-end">
                <div
                  className="inline-flex w-[146px] items-center gap-0.5 rounded-lg p-0.5"
                  style={{
                    background: 'color-mix(in oklch, var(--ink-500) 8%, transparent)',
                    border: '0.5px solid color-mix(in oklch, var(--ink-500) 10%, transparent)',
                    opacity: (!providerSelection || !providerSelectionMutable) ? 0.5 : 1,
                    transition: 'opacity 0.2s',
                  }}
                >
                  {!providerSelection
                    ? (
                      <span className="flex-1 py-1 text-center text-[10px]" style={{ color: 'var(--ink-400)' }}>
                        等待中...
                      </span>
                    )
                    : explanationProviderOptions.map((provider) => {
                      const isActive = providerSelection.explanation_provider === provider.provider;
                      const isUnavailable = !provider.available;
                      const isInteractive = !isUnavailable && !providerSelectionPending && providerSelectionMutable;
                      return (
                        <button
                          key={provider.provider}
                          type="button"
                          disabled={isUnavailable || providerSelectionPending || !providerSelectionMutable}
                          title={isUnavailable ? (provider.disabled_reason ?? '不可用') : undefined}
                          onClick={() => {
                            if (!isActive && isInteractive) {
                              setProviderSelection({ explanation_provider: provider.provider as 'gemini' | 'zhipu' });
                            }
                          }}
                          className="flex-1 px-3 py-1 text-[10px] font-medium transition-all duration-200"
                          style={{
                            borderRadius: '6px',
                            border: 'none',
                            background: isActive
                              ? (isDarkMode ? 'rgba(255,255,255,0.12)' : 'white')
                              : 'transparent',
                            boxShadow: isActive ? '0 1px 3px rgba(0,0,0,0.1)' : 'none',
                            color: isActive ? 'var(--accent)' : (isUnavailable ? 'var(--ink-400)' : 'var(--ink-500)'),
                            cursor: isInteractive ? (isActive ? 'default' : 'pointer') : 'not-allowed',
                            opacity: isUnavailable ? 0.4 : 1,
                          }}
                        >
                          {provider.display_name}
                        </button>
                      );
                    })
                  }
                </div>
              </div>
            </div>

            {providerSelectionError && (
              <div className="text-[10px]" style={{ color: 'var(--risk-alarm)' }}>
                {providerSelectionError}
              </div>
            )}

            <div className="flex items-center">
              <div className="w-[140px] flex-shrink-0">
                <div className="text-[11px] font-semibold" style={{ color: 'var(--ink-700)' }}>
                  本船视角
                </div>
                <div className="text-[10px] leading-tight" style={{ color: 'var(--ink-500)' }}>
                  镜头跟随策略
                </div>
              </div>
              <div className="flex flex-1 justify-end">
                <div
                  className="inline-flex w-[146px] items-center gap-0.5 rounded-lg p-0.5"
                  style={{
                    background: 'color-mix(in oklch, var(--ink-500) 8%, transparent)',
                    border: '0.5px solid color-mix(in oklch, var(--ink-500) 10%, transparent)',
                  }}
                >
                  {([
                    { mode: 'SOFT' as const, label: '浅跟随' },
                    { mode: 'LOCKED' as const, label: '锁定' },
                  ]).map(({ mode, label }) => {
                    const isActive = followMode === mode;
                    return (
                      <button
                        key={mode}
                        type="button"
                        onClick={() => setFollowMode(mode)}
                        className="flex-1 px-2.5 py-1 text-[10px] font-medium transition-all duration-200"
                        style={{
                          borderRadius: '6px',
                          border: 'none',
                          background: isActive
                            ? (isDarkMode ? 'rgba(255,255,255,0.12)' : 'white')
                            : 'transparent',
                          boxShadow: isActive ? '0 1px 3px rgba(0,0,0,0.1)' : 'none',
                          color: isActive ? 'var(--accent)' : 'var(--ink-500)',
                          cursor: 'pointer',
                        }}
                      >
                        {label}
                      </button>
                    );
                  })}
                </div>
              </div>
            </div>

            <div>
              <div className="mb-2 flex items-center justify-between">
                <div>
                  <div className="text-[10px] font-semibold" style={{ color: 'var(--ink-700)' }}>
                    安全等深线
                  </div>
                  <div className="text-[9px]" style={{ color: 'var(--ink-500)' }}>
                    水文图层显示深度阈值
                  </div>
                </div>
                <span
                  className="rounded-full px-2 py-0.5 font-mono text-[10px]"
                  style={{
                    background: safetyContourOverride === null
                      ? 'color-mix(in oklch, var(--risk-safe) 12%, transparent)'
                      : 'color-mix(in oklch, var(--risk-warning) 12%, transparent)',
                    color: safetyContourOverride === null ? 'var(--risk-safe)' : 'var(--risk-warning)',
                  }}
                >
                  {effectiveSafetyContourVal.toFixed(1)}m
                </span>
              </div>
              <input
                aria-label="Safety contour value"
                type="range"
                min={CONTOUR_MIN}
                max={CONTOUR_MAX}
                step={CONTOUR_STEP}
                value={Math.round(effectiveSafetyContourVal)}
                onChange={(event) => setSafetyContourOverride(Number(event.target.value))}
                className="h-1.5 w-full cursor-pointer appearance-none rounded-full"
                style={{ background: 'color-mix(in oklch, var(--ink-500) 15%, transparent)' }}
              />
              <div className="mt-1 flex items-center justify-between">
                <span className="font-mono text-[9px]" style={{ color: 'var(--ink-500)' }}>
                  {CONTOUR_MIN}m
                </span>
                <button
                  type="button"
                  disabled={safetyContourOverride === null}
                  onClick={() => setSafetyContourOverride(null)}
                  className="rounded-full px-2 py-0.5 text-[9px] font-medium transition disabled:cursor-default disabled:opacity-40"
                  style={{
                    background: 'color-mix(in oklch, var(--ink-500) 10%, transparent)',
                    color: 'var(--ink-700)',
                  }}
                >
                  恢复实时值
                </button>
                <span className="font-mono text-[9px]" style={{ color: 'var(--ink-500)' }}>
                  {CONTOUR_MAX}m
                </span>
              </div>
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
              <div className="flex items-center gap-2">
                {resolvedExplanations.length > 0 && (
                  <button
                    type="button"
                    onClick={clearExpiredExplanations}
                    className="rounded-lg px-2 py-0.5 text-[9px]"
                    style={{
                      border: '0.5px solid color-mix(in oklch, var(--ink-500) 20%, transparent)',
                      color: 'var(--ink-500)',
                      cursor: 'pointer',
                      background: 'transparent',
                    }}
                  >
                    清理过期
                  </button>
                )}
                <span className="tnum font-mono text-[10px]" style={{ color: 'var(--ink-500)' }}>
                  {sortedExplainedTargets.length} 目标
                </span>
              </div>
            </div>

            {visibleRiskError && (
              <div
                className="anim-soft-pulse px-5 py-1.5 text-[10px] font-medium"
                style={{ color: 'var(--risk-alarm)' }}
              >
                {visibleRiskError.kind === 'connection' ? '服务连接异常' : '风险事件异常'}：{visibleRiskError.message}
              </div>
            )}

            {activeAdvisory && (
              <div className="px-4 pt-3 pb-1">
                <AdvisoryCard isDarkMode={isDarkMode} />
              </div>
            )}

            <div className="scrollbar-apple flex-1 min-h-0 space-y-2.5 overflow-y-auto p-4">
              {displayedCards.length === 0 && resolvedExplanations.length === 0 ? (
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
                <>
                  {displayedCards.map(({ target, explanation, isLeaving, renderKey }) => {
                    const color = riskColors[explanation.risk_level];
                    const isSelected = selectedTargetIds.includes(target.id);
                    const encounterLabel = translateEncounterType(target.risk_assessment.encounter_type);

                    return (
                      <div
                        key={renderKey}
                        onClick={() => selectTarget(target.id)}
                        className={`${isLeaving ? 'card-exit' : 'anim-rise'} cursor-pointer overflow-hidden rounded-2xl transition-all duration-200`}
                        style={{
                          transformOrigin: 'top center',
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
                  })}
                  {resolvedExplanations.length > 0 && (
                    <div className="space-y-2.5" style={{ opacity: 0.65 }}>
                      {[...resolvedExplanations].sort((a, b) => {
                        const aTime = a.resolved_at ? Date.parse(a.resolved_at) : 0;
                        const bTime = b.resolved_at ? Date.parse(b.resolved_at) : 0;
                        return bTime - aTime;
                      }).map((explanation) => {
                        const color = riskColors[explanation.risk_level];
                        const resolvedAtFormatted = explanation.resolved_at
                          ? new Date(explanation.resolved_at).toLocaleTimeString('zh-CN', {
                              hour: '2-digit',
                              minute: '2-digit',
                              second: '2-digit',
                            })
                          : null;
                        const resolvedReasonLabel = explanation.resolved_reason === 'TARGET_SAFE'
                          ? '已转安全'
                          : explanation.resolved_reason === 'TARGET_MISSING'
                            ? '已离开'
                            : '已解除';

                        return (
                          <div
                            key={explanation.event_id}
                            className="overflow-hidden rounded-2xl"
                            style={{
                              background: isDarkMode ? 'rgba(255,255,255,0.02)' : 'rgba(255,255,255,0.4)',
                              border: `0.5px solid color-mix(in oklch, ${color} 12%, transparent)`,
                            }}
                          >
                            <div
                              className="flex items-center px-4 py-2.5"
                              style={{ borderBottom: `0.5px solid color-mix(in oklch, ${color} 10%, transparent)` }}
                            >
                              <div className="min-w-0 flex-1">
                                <div className="mb-0.5 flex items-center gap-2">
                                  <span
                                    className="tnum font-mono text-[12px] font-semibold"
                                    style={{ color: 'var(--ink-700)' }}
                                  >
                                    {explanation.target_id}
                                  </span>
                                  <span
                                    className="rounded-full px-1.5 py-0.5 text-[9px] font-semibold"
                                    style={{
                                      color: 'var(--ink-500)',
                                      background: 'color-mix(in oklch, var(--ink-500) 10%, transparent)',
                                    }}
                                  >
                                    {resolvedReasonLabel}
                                  </span>
                                  <span
                                    className="ml-auto rounded-full px-1.5 py-0.5 text-[9px] font-medium"
                                    style={{
                                      color,
                                      background: `color-mix(in oklch, ${color} 10%, transparent)`,
                                    }}
                                  >
                                    原 {riskLabels[explanation.risk_level]}
                                  </span>
                                </div>
                                <div className="text-[10px]" style={{ color: 'var(--ink-500)' }}>
                                  {explanation.provider}{resolvedAtFormatted && ` · ${resolvedAtFormatted}`}
                                </div>
                              </div>
                            </div>

                            <div className="px-4 py-3">
                              <p
                                className="text-[12px] leading-relaxed"
                                style={{ color: 'var(--ink-700)' }}
                              >
                                {explanation.text}
                              </p>
                              <div className="mt-2 flex justify-end">
                                <button
                                  type="button"
                                  onClick={() => {
                                    setPendingExplanationRef({
                                      target_id: explanation.target_id,
                                      explanation_event_id: explanation.event_id,
                                    });
                                    selectTarget(explanation.target_id);
                                  }}
                                  className="rounded-lg px-2.5 py-1 text-[10px] font-medium"
                                  style={{
                                    border: '0.5px solid color-mix(in oklch, var(--accent) 30%, transparent)',
                                    color: 'var(--accent)',
                                    cursor: 'pointer',
                                    background: 'color-mix(in oklch, var(--accent) 8%, transparent)',
                                  }}
                                >
                                  追问
                                </button>
                              </div>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </>
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
              <div className="flex items-center gap-2.5">
                <span className="text-[11px] font-semibold" style={{ color: 'var(--ink-900)' }}>
                  智能决策助理
                </span>
                <div
                  className="flex items-center rounded-lg overflow-hidden"
                  style={{
                    border: '0.5px solid color-mix(in oklch, var(--ink-500) 20%, transparent)',
                    background: 'color-mix(in oklch, var(--ink-500) 5%, transparent)',
                  }}
                >
                  {(['CHAT', 'AGENT'] as const).map((mode) => {
                    const isActive = assistantMode === mode;
                    const isCapabilityReady = chatCapabilityState === 'ready';
                    const isAvailable = mode === 'CHAT'
                      ? !isCapabilityReady || (chatCapability?.chat_available ?? true)
                      : !isCapabilityReady || (chatCapability?.agent_available ?? false);
                    return (
                      <button
                        key={mode}
                        type="button"
                        disabled={!isAvailable}
                        onClick={() => setAssistantMode(mode)}
                        className="px-2 py-0.5 text-[9px] font-semibold transition-colors"
                        style={{
                          background: isActive
                            ? 'color-mix(in oklch, var(--accent) 18%, transparent)'
                            : 'transparent',
                          color: isActive
                            ? 'var(--accent)'
                            : 'var(--ink-500)',
                          cursor: isAvailable ? 'pointer' : 'not-allowed',
                          opacity: isAvailable ? 1 : 0.4,
                          border: 'none',
                        }}
                        title={!isAvailable && isCapabilityReady
                          ? (mode === 'AGENT'
                            ? (chatCapability?.disabled_reasons?.agent ?? 'Agent 模式不可用')
                            : (chatCapability?.disabled_reasons?.chat ?? 'Chat 模式不可用'))
                          : undefined}
                      >
                        {mode}
                      </button>
                    );
                  })}
                </div>
              </div>
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
              agentStepsByReplyToEventId={agentStepsByReplyToEventId}
            />

            <ChatComposer
              value={chatInput}
              disabled={isChatSending || chatCapabilityState !== 'ready'}
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
