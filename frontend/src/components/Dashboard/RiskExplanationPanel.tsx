import { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import {
  useRiskStore,
  useAiCenterStore,
  selectAiCenterOpenRequestVersion,
  selectSelectedTargetIds,
  selectDroppedTargetNotices,
  selectTargets,
  selectExplanationsByTargetId,
  selectChatMessages,
  selectChatInput,
  selectEditingDraft,
  selectEditingMessageEventId,
  selectEditingSubmitError,
  selectEditingSubmitEventId,
  selectIsChatSending,
  selectSpeechEnabled,
  selectSpeechSupported,
} from '../../store';
import { useThemeStore } from '../../store/useThemeStore';
import { speechService } from '../../services/speechService';
import { getRiskColor } from '../../config';
import { useVoiceCapture } from '../../hooks/useVoiceCapture';
import { translateEncounterType } from '../../utils/riskDisplay';
import type { AiCenterChatMessage } from '../../types/aiCenter';
import type { ExplanationPayload, RiskLevel, RiskTarget } from '../../types/schema';
import { ChatComposer } from './ChatComposer';
import { ChatMessageList } from './ChatMessageList';

const PANEL_WIDTH = 380; // Slightly widened as per plan

export function RiskExplanationPanel() {
  const targets = useRiskStore(selectTargets);
  const explanationsByTargetId = useRiskStore(selectExplanationsByTargetId);
  const selectedTargetIds = useRiskStore(selectSelectedTargetIds);
  const droppedTargetNotices = useRiskStore(selectDroppedTargetNotices);
  const selectTarget = useRiskStore((state) => state.selectTarget);
  const deselectTarget = useRiskStore((state) => state.deselectTarget);
  const clearDroppedTargetNotices = useRiskStore((state) => state.clearDroppedTargetNotices);

  const aiCenterOpenRequestVersion = useAiCenterStore(selectAiCenterOpenRequestVersion);
  const chatMessages = useAiCenterStore(selectChatMessages);
  const chatInput = useAiCenterStore(selectChatInput);
  const editingMessageEventId = useAiCenterStore(selectEditingMessageEventId);
  const editingDraft = useAiCenterStore(selectEditingDraft);
  const editingSubmitEventId = useAiCenterStore(selectEditingSubmitEventId);
  const editingSubmitError = useAiCenterStore(selectEditingSubmitError);
  const isChatSending = useAiCenterStore(selectIsChatSending);
  const isChatFocused = useAiCenterStore((state) => state.isChatFocused);
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
  const setIsChatFocused = useAiCenterStore((state) => state.setIsChatFocused);
  const setSpeechEnabled = useAiCenterStore((state) => state.setSpeechEnabled);
  const setSpeechUnlocked = useAiCenterStore((state) => state.setSpeechUnlocked);

  const { isDarkMode, toggleTheme } = useThemeStore();

  const {
    voiceCaptureSupported,
    voiceCaptureState,
    voiceCaptureError,
    activeVoiceMode,
    handleStartVoiceRecording,
    handleStopVoiceRecording,
    cancelVoiceCapture,
  } = useVoiceCapture();

  const lastSseError = useRiskStore((state) => state.lastError);
  const clearRiskError = useRiskStore((state) => state.clearRiskError);

  const [isHovered, setIsHovered] = useState(false);
  const [topSectionHeight, setTopSectionHeight] = useState(60);
  const [chatSendError, setChatSendError] = useState<string | null>(null);
  const [visibleSseError, setVisibleSseError] = useState<string | null>(null);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);

  useEffect(() => {
    if (!lastSseError) return;
    setVisibleSseError(lastSseError.error_message);
    const timer = setTimeout(() => {
      setVisibleSseError(null);
      clearRiskError();
    }, 4000);
    return () => clearTimeout(timer);
  }, [lastSseError, clearRiskError]);
  const isDragging = useRef(false);
  const timeoutRef = useRef<ReturnType<typeof setTimeout>>();

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
      .sort((l, r) => getRiskPriority(r.explanation.risk_level) - getRiskPriority(l.explanation.risk_level))
  ), [targets, explanationsByTargetId]);

  const selectedTargetsForChip = useMemo(() => {
    const chips: Array<{ id: string; riskLevel: string }> = [];
    for (const id of selectedTargetIds) {
      const target = targets.find((t) => t.id === id);
      if (target) {
        chips.push({ id: target.id, riskLevel: target.risk_assessment.risk_level });
      }
    }
    return chips;
  }, [selectedTargetIds, targets]);

  const handleMouseEnter = () => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
    }
    setIsHovered(true);
  };

  const handleMouseLeave = () => {
    if (isChatFocused || isDragging.current) {
      return;
    }

    timeoutRef.current = setTimeout(() => {
      setIsHovered(false);
    }, 300);
  };

  useEffect(() => {
    if (!isChatFocused && !isHovered) {
      handleMouseLeave();
    }
  }, [isChatFocused, isHovered]);

  useEffect(() => {
    if (aiCenterOpenRequestVersion > 0) {
      handleMouseEnter();
    }
  }, [aiCenterOpenRequestVersion]);

  const handleSendChat = () => {
    const sent = sendTextMessage(chatInput);
    if (!sent) {
      setChatSendError('发送失败：连接已断开');
      setTimeout(() => setChatSendError(null), 3000);
    }
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
    if (!speechSupported) return;
    if (speechEnabled) {
      setSpeechEnabled(false);
      speechService.stop();
    } else {
      const unlocked = speechService.unlock();
      setSpeechUnlocked(unlocked);
      setSpeechEnabled(unlocked);
    }
  };

  const handleMouseDown = () => {
    isDragging.current = true;
    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);
  };

  const handleMouseMove = useCallback((event: MouseEvent) => {
    if (!isDragging.current) return;
    const panelRect = document.getElementById('ai-center-panel')?.getBoundingClientRect();
    if (!panelRect) return;

    const relativeY = event.clientY - panelRect.top;
    let newPercentage = (relativeY / panelRect.height) * 100;
    newPercentage = Math.max(20, Math.min(80, newPercentage));
    setTopSectionHeight(newPercentage);
  }, []);

  const handleMouseUp = useCallback(() => {
    isDragging.current = false;
    document.removeEventListener('mousemove', handleMouseMove);
    document.removeEventListener('mouseup', handleMouseUp);
    if (!isChatFocused && !isHovered) handleMouseLeave();
  }, [handleMouseMove, isChatFocused, isHovered]);

  const isVisible = isHovered || isChatFocused || voiceCaptureState === 'recording' || voiceCaptureState === 'transcribing';

  return (
    <div
      className="fixed right-0 top-0 bottom-0 flex pointer-events-none z-50 transition-transform duration-500 ease-[cubic-bezier(0.16,1,0.3,1)]"
      style={{
        transform: isVisible ? 'translateX(0)' : `translateX(${PANEL_WIDTH}px)`,
      }}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
    >
      {/* 全高边缘句柄 */}
      <div className="w-8 h-full pointer-events-auto flex items-center justify-end pr-1 cursor-w-resize group">
        <div
          className={`w-1 h-32 rounded-full transition-all duration-300 ${
            isVisible
              ? 'bg-cyan-500/60 shadow-[0_0_8px_rgba(6,182,212,0.4)]'
              : 'bg-white/10 group-hover:bg-cyan-400/80 group-hover:h-40'
          }`}
        />
      </div>

      <div
        id="ai-center-panel"
        className="h-full bg-white/95 dark:bg-slate-950/85 backdrop-blur-xl border-l border-slate-200 dark:border-white/10 shadow-2xl flex flex-col pointer-events-auto overflow-hidden relative transition-colors duration-300"
        style={{ width: `${PANEL_WIDTH}px` }}
      >
        {/* 顶部标题栏 */}
        <div className="px-4 py-3 border-b border-slate-200 dark:border-white/10 shrink-0 bg-gradient-to-r from-transparent to-cyan-50 dark:to-cyan-950/20">
          <div className="flex items-center justify-between gap-2">
            <h2 className="text-sm font-bold tracking-wide text-slate-800 dark:text-slate-100 flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-cyan-500 dark:bg-cyan-400 animate-pulse"></span>
              AI 航海助手
              </h2>
            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={() => setIsSettingsOpen(!isSettingsOpen)}
                className={`p-1.5 rounded transition-colors ${
                  isSettingsOpen
                    ? 'bg-cyan-100 dark:bg-cyan-900/40 text-cyan-600 dark:text-cyan-400'
                    : 'text-slate-400 hover:text-slate-600 dark:hover:text-slate-200'
                }`}
                title="系统设置"
              >
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                </svg>
              </button>
              <span className="text-[10px] bg-slate-200 dark:bg-slate-800 text-slate-500 dark:text-slate-400 px-1.5 py-0.5 rounded font-mono">
                {chatMessages.length} 条消息
              </span>
            </div>
          </div>
        </div>

        {/* 系统设置折叠区 */}
        <div
          className={`overflow-hidden transition-all duration-300 ease-[cubic-bezier(0.16,1,0.3,1)] bg-slate-50/50 dark:bg-slate-900/30 border-b border-slate-200 dark:border-white/10 ${
            isSettingsOpen ? 'max-h-32 opacity-100' : 'max-h-0 opacity-0'
          }`}
        >
          <div className="p-4 space-y-3">
            <div className="flex items-center justify-between">
              <div>
                <div className="text-[10px] text-slate-500 dark:text-slate-400 font-bold uppercase tracking-wider">显示模式</div>
                <div className="text-[9px] text-slate-400">切换深色/浅色主题</div>
              </div>
              <button
                onClick={toggleTheme}
                className="px-3 py-1 rounded text-[10px] font-medium border border-slate-300 dark:border-white/10 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 hover:border-cyan-500/50 transition-colors"
              >
                {isDarkMode ? '深色模式' : '浅色模式'}
              </button>
            </div>
            <div className="flex items-center justify-between">
              <div>
                <div className="text-[10px] text-slate-500 dark:text-slate-400 font-bold uppercase tracking-wider">语音播报</div>
                <div className="text-[9px] text-slate-400">{speechSupported ? '自动播报 AI 评估' : '浏览器不支持'}</div>
              </div>
              <button
                onClick={handleSpeechToggle}
                disabled={!speechSupported}
                className={`px-3 py-1 rounded text-[10px] font-medium border transition-all ${
                  speechEnabled
                    ? 'border-cyan-500/50 bg-cyan-500/10 text-cyan-600 dark:text-cyan-400'
                    : 'border-slate-300 dark:border-white/10 bg-white dark:bg-slate-800 text-slate-500'
                }`}
              >
                {speechEnabled ? '已开启' : '已关闭'}
              </button>
            </div>
          </div>
        </div>

        <div className="flex-1 min-h-0 flex flex-col">
          {/* 态势监控日志区 */}
          <section
            style={{ height: `${topSectionHeight}%` }}
            className="min-h-0 border-b border-slate-200 dark:border-white/10 flex flex-col"
          >
            <div className="px-4 py-2.5 shrink-0 flex items-center justify-between text-[11px] uppercase tracking-[0.18em] text-slate-500 dark:text-slate-400">
              <span>风险评估</span>
              <span>{sortedExplainedTargets.length} 个目标</span>
            </div>
            {visibleSseError && (
              <div className="px-4 pb-2 text-[10px] text-red-500 dark:text-red-400/80 animate-pulse shrink-0 font-medium">
                服务连接异常: {visibleSseError}
              </div>
            )}

            <div className="flex-1 min-h-0 overflow-y-auto p-3 space-y-3 scrollbar-thin scrollbar-thumb-slate-300 dark:scrollbar-thumb-slate-800 transition-colors duration-300">
              {sortedExplainedTargets.length === 0 ? (
                <div className="h-full flex flex-col items-center justify-center px-6 text-center">
                  <div className="w-12 h-12 rounded-full bg-slate-100 dark:bg-slate-900 flex items-center justify-center mb-3">
                    <svg className="w-6 h-6 text-slate-300 dark:text-slate-700" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                    </svg>
                  </div>
                  <p className="text-xs text-slate-400 dark:text-slate-500">当前航区暂无风险评估</p>
                </div>
              ) : (
                sortedExplainedTargets.map(({ target, explanation }) => {
                  const isSelected = selectedTargetIds.includes(target.id);
                  const riskColor = getRiskColor(explanation.risk_level);
                  const riskHex = `rgb(${riskColor.join(',')})`;
                  const encounterTypeText = translateEncounterType(target.risk_assessment.encounter_type);

                  return (
                    <div
                      key={explanation.event_id}
                      onClick={() => selectTarget(target.id)}
                      className={`p-3 rounded-lg border transition-all duration-200 cursor-pointer ${
                        isSelected
                          ? 'border-cyan-500/50 bg-cyan-50 dark:bg-cyan-950/30 shadow-sm'
                          : 'border-slate-100 dark:border-white/5 bg-slate-50/50 dark:bg-slate-900/50 hover:border-slate-300 dark:hover:border-white/10'
                      }`}
                    >
                      <div className="flex justify-between items-center mb-2 gap-2">
                        <div className="flex items-center gap-2 min-w-0">
                          <span className="font-mono text-xs text-slate-700 dark:text-slate-200 truncate font-bold">ID: {target.id}</span>
                          {encounterTypeText && (
                            <span className="text-[9px] px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-800 text-slate-600 dark:text-slate-400 font-bold uppercase tracking-tight">
                              {encounterTypeText}
                            </span>
                          )}
                        </div>
                        <span
                          className="text-[9px] font-bold px-1.5 py-0.5 rounded uppercase tracking-wider"
                          style={{ color: riskHex, backgroundColor: `rgba(${riskColor.join(',')}, 0.15)` }}
                        >
                          {explanation.risk_level}
                        </span>
                      </div>

                      <div className="text-[12px] leading-relaxed text-slate-700 dark:text-slate-300 whitespace-pre-wrap font-medium">
                        {explanation.text}
                      </div>

                      <div className="mt-2 pt-2 border-t border-slate-200 dark:border-white/5 flex justify-between items-center text-[9px] text-slate-400 dark:text-slate-500 uppercase tracking-widest">
                        <span>{explanation.provider}</span>
                        <span>{explanation.timestamp}</span>
                      </div>
                    </div>
                  );
                })
              )}
            </div>
          </section>

          {/* 拖动分割条 */}
          <div
            className="h-1 bg-slate-100 dark:bg-white/5 hover:bg-cyan-500/50 cursor-row-resize transition-colors"
            onMouseDown={handleMouseDown}
          />

          {/* 对话区 */}
          <section
            style={{ height: `${100 - topSectionHeight}%` }}
            className="min-h-0 flex flex-col"
          >
            <div className="px-4 py-2.5 shrink-0 flex items-center justify-between text-[11px] uppercase tracking-[0.18em] text-slate-500 dark:text-slate-400">
              <span>智能决策助理</span>
              <button
                type="button"
                onClick={handleResetConversation}
                className="rounded border border-slate-200 dark:border-white/10 px-2 py-0.5 text-[9px] tracking-normal text-slate-400 hover:border-cyan-500/50 hover:text-cyan-600 dark:hover:text-cyan-400 transition-all"
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
              onFocus={() => setIsChatFocused(true)}
              onBlur={() => setIsChatFocused(false)}
            />
          </section>
        </div>
      </div>
    </div>
  );
}

function shouldShowRiskCard(level: RiskLevel): boolean {
  return level === 'CAUTION' || level === 'WARNING' || level === 'ALARM';
}

function getRiskPriority(level: RiskLevel): number {
  switch (level) {
    case 'ALARM': return 3;
    case 'WARNING': return 2;
    case 'CAUTION': return 1;
    default: return 0;
  }
}
