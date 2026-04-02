import { useEffect, useRef, useState, useCallback } from 'react';
import {
  useRiskStore,
  useAiCenterStore,
  selectAiCenterOpenRequestVersion,
  selectSelectedTarget,
  selectExplainedTargets,
  selectChatMessages,
  selectChatInput,
  selectIsChatSending,
  selectVoiceCaptureError,
  selectVoiceCaptureState,
  selectVoiceCaptureSupported,
  selectActiveVoiceMode,
} from '../../store';
import { CHAT_CONFIG, getRiskColor } from '../../config';
import { voiceRecorderService } from '../../services';
import type { AiCenterChatMessage } from '../../types/aiCenter';
import type { SpeechMode, RiskLevel } from '../../types/schema';
import { ChatComposer } from './ChatComposer';
import { ChatMessageList } from './ChatMessageList';

const PANEL_WIDTH = 360;
const PANEL_HEIGHT = '72vh';

export function RiskExplanationPanel() {
  const explainedTargets = useRiskStore(selectExplainedTargets);
  const selectedTarget = useRiskStore(selectSelectedTarget);
  const selectTarget = useRiskStore((state) => state.selectTarget);

  const aiCenterOpenRequestVersion = useAiCenterStore(selectAiCenterOpenRequestVersion);
  const chatMessages = useAiCenterStore(selectChatMessages);
  const chatInput = useAiCenterStore(selectChatInput);
  const isChatSending = useAiCenterStore(selectIsChatSending);
  const voiceCaptureSupported = useAiCenterStore(selectVoiceCaptureSupported);
  const voiceCaptureState = useAiCenterStore(selectVoiceCaptureState);
  const voiceCaptureError = useAiCenterStore(selectVoiceCaptureError);
  const activeVoiceMode = useAiCenterStore(selectActiveVoiceMode);

  const isChatFocused = useAiCenterStore((state) => state.isChatFocused);

  const setChatInput = useAiCenterStore((state) => state.setChatInput);
  const sendTextMessage = useAiCenterStore((state) => state.sendTextMessage);
  const sendSpeechMessage = useAiCenterStore((state) => state.sendSpeechMessage);
  const resetConversation = useAiCenterStore((state) => state.resetConversation);
  const setIsChatFocused = useAiCenterStore((state) => state.setIsChatFocused);
  const setVoiceCaptureSupported = useAiCenterStore((state) => state.setVoiceCaptureSupported);
  const setVoiceCaptureRecording = useAiCenterStore((state) => state.setVoiceCaptureRecording);
  const setVoiceCaptureError = useAiCenterStore((state) => state.setVoiceCaptureError);
  const resetVoiceCapture = useAiCenterStore((state) => state.resetVoiceCapture);

  const [isHovered, setIsHovered] = useState(false);
  const [topSectionHeight, setTopSectionHeight] = useState(60);
  const isDragging = useRef(false);
  const timeoutRef = useRef<ReturnType<typeof setTimeout>>();
  const voiceSentResetTimerRef = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    setVoiceCaptureSupported(voiceRecorderService.isSupported());

    return () => {
      if (voiceSentResetTimerRef.current) {
        clearTimeout(voiceSentResetTimerRef.current);
      }
      voiceRecorderService.cancelRecording();
    };
  }, [setVoiceCaptureSupported]);

  useEffect(() => {
    if (voiceCaptureState !== 'sent') {
      if (voiceSentResetTimerRef.current) {
        clearTimeout(voiceSentResetTimerRef.current);
      }
      return;
    }

    voiceSentResetTimerRef.current = setTimeout(() => {
      resetVoiceCapture();
    }, CHAT_CONFIG.VOICE_SENT_RESET_DELAY_MS);

    return () => {
      if (voiceSentResetTimerRef.current) {
        clearTimeout(voiceSentResetTimerRef.current);
      }
    };
  }, [resetVoiceCapture, voiceCaptureState]);

  const sortedExplainedTargets = [...explainedTargets]
    .filter(({ explanation }) => shouldShowRiskCard(explanation.risk_level))
    .sort((left, right) => getRiskPriority(right.explanation.risk_level) - getRiskPriority(left.explanation.risk_level));

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
    sendTextMessage(chatInput);
  };

  const handleRetry = (message: AiCenterChatMessage) => {
    if (message.role !== 'user' || message.request_type === 'SPEECH') {
      return;
    }

    sendTextMessage(message.content);
  };

  const handleStartVoiceRecording = async () => {
    try {
      await voiceRecorderService.startRecording();
      setVoiceCaptureRecording();
    } catch (error) {
      setVoiceCaptureError(getVoiceErrorMessage(error), null);
    }
  };

  const handleStopVoiceRecording = async (mode: SpeechMode) => {
    try {
      const { blob, audioFormat } = await voiceRecorderService.stopRecording();

      if (blob.size === 0) {
        throw new Error('未采集到有效语音，请重新录音');
      }

      if (blob.size > CHAT_CONFIG.MAX_AUDIO_SIZE_BYTES) {
        throw new Error('录音文件超过 10 MiB，请缩短录音时长');
      }

      const audioData = await voiceRecorderService.blobToBase64(blob);
      const didSend = sendSpeechMessage({
        audioData,
        audioFormat,
        mode,
      });

      if (!didSend) {
        setVoiceCaptureError('语音消息发送失败，请重新录音', null);
      }
    } catch (error) {
      setVoiceCaptureError(getVoiceErrorMessage(error), null);
    }
  };

  const handleResetConversation = () => {
    voiceRecorderService.cancelRecording();
    resetVoiceCapture();
    resetConversation();
  };

  const handleMouseDown = () => {
    isDragging.current = true;
    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);
  };

  const handleMouseMove = useCallback((event: MouseEvent) => {
    if (!isDragging.current) {
      return;
    }

    const panelRect = document.getElementById('ai-center-panel')?.getBoundingClientRect();
    if (!panelRect) {
      return;
    }

    const relativeY = event.clientY - panelRect.top;
    let newPercentage = (relativeY / panelRect.height) * 100;
    newPercentage = Math.max(20, Math.min(80, newPercentage));

    setTopSectionHeight(newPercentage);
  }, []);

  const handleMouseUp = useCallback(() => {
    isDragging.current = false;
    document.removeEventListener('mousemove', handleMouseMove);
    document.removeEventListener('mouseup', handleMouseUp);

    if (!isChatFocused && !isHovered) {
      handleMouseLeave();
    }
  }, [handleMouseMove, isChatFocused, isHovered]);

  return (
    <div
      className="fixed right-0 top-1/2 flex pointer-events-none z-50 transition-transform duration-500 ease-[cubic-bezier(0.16,1,0.3,1)]"
      style={{
        height: PANEL_HEIGHT,
        transform: isHovered || isChatFocused || voiceCaptureState === 'recording' || voiceCaptureState === 'transcribing'
          ? 'translateY(-50%) translateX(0)'
          : `translateY(-50%) translateX(${PANEL_WIDTH}px)`,
      }}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
    >
      <div className="w-8 h-full pointer-events-auto flex items-center justify-end pr-1 cursor-w-resize group">
        <div
          className={`w-1 h-20 rounded-full transition-all duration-300 ${
            isHovered || isChatFocused
              ? 'bg-cyan-500/60 shadow-[0_0_8px_rgba(6,182,212,0.4)]'
              : 'bg-white/10 group-hover:bg-cyan-400/80 group-hover:h-24'
          }`}
        />
      </div>

      <div
        id="ai-center-panel"
        className="h-full bg-white/95 dark:bg-slate-950/85 backdrop-blur-xl border-l border-slate-200 dark:border-white/10 shadow-lg rounded-l-2xl flex flex-col pointer-events-auto overflow-hidden relative transition-colors duration-300"
        style={{ width: `${PANEL_WIDTH}px` }}
      >
        <div className="px-4 py-3 border-b border-slate-200 dark:border-white/10 shrink-0 bg-gradient-to-r from-transparent to-cyan-50 dark:to-cyan-950/20 transition-colors duration-300">
          <div className="flex items-center justify-between gap-2">
            <h2 className="text-sm font-bold tracking-wide text-slate-800 dark:text-slate-100 flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-cyan-500 dark:bg-cyan-400 animate-pulse"></span>
              AI 助理中心
            </h2>
            <span className="text-[10px] bg-slate-200 dark:bg-slate-800 text-slate-500 dark:text-slate-400 px-1.5 py-0.5 rounded font-mono">
              {sortedExplainedTargets.length} / {chatMessages.length}
            </span>
          </div>
        </div>

        <div className="flex-1 min-h-0 flex flex-col">
          <section
            style={{ height: `${topSectionHeight}%` }}
            className="min-h-0 border-b border-slate-300 dark:border-white/10 flex flex-col"
          >
            <div className="px-4 py-2.5 shrink-0 flex items-center justify-between text-[11px] uppercase tracking-[0.18em] text-slate-500 dark:text-slate-400">
              <span>态势监控日志</span>
              <span>{sortedExplainedTargets.length} 个风险目标</span>
            </div>

            <div className="flex-1 min-h-0 overflow-y-auto p-3 space-y-3 scrollbar-thin relative transition-colors duration-300">
              {sortedExplainedTargets.length === 0 ? (
                <div className="absolute inset-0 flex items-center justify-center px-6 text-center text-xs text-slate-500">
                  当前航区暂无 AI 风险评估
                </div>
              ) : (
                sortedExplainedTargets.map(({ target, explanation }) => {
                  const isSelected = selectedTarget?.id === target.id;
                  const riskColor = getRiskColor(explanation.risk_level);
                  const riskHex = `rgb(${riskColor.join(',')})`;

                  return (
                    <div
                      key={explanation.event_id}
                      onClick={() => {
                        selectTarget(target.id);
                      }}
                      className={`p-3 rounded-lg border transition-all duration-200 cursor-pointer ${
                        isSelected
                          ? 'border-cyan-500/50 bg-cyan-50 dark:bg-cyan-950/30 shadow-[inset_0_0_20px_rgba(6,182,212,0.05)]'
                          : 'border-slate-200 dark:border-white/5 bg-slate-50 dark:bg-slate-900/50 hover:bg-slate-100 dark:hover:bg-slate-800/80'
                      }`}
                    >
                      <div className="flex justify-between items-center mb-2 gap-2">
                        <div className="flex items-center gap-2 min-w-0">
                          <span className="font-mono text-xs text-slate-700 dark:text-slate-200 truncate">ID: {target.id}</span>
                          <span className="h-2 w-2 rounded-full bg-cyan-500 dark:bg-cyan-400 shadow-[0_0_10px_rgba(34,211,238,0.8)]"></span>
                        </div>
                        <span
                          className="text-[9px] font-bold px-1.5 py-0.5 rounded uppercase"
                          style={{ color: riskHex, backgroundColor: `rgba(${riskColor.join(',')}, 0.15)` }}
                        >
                          {explanation.risk_level}
                        </span>
                      </div>

                      <div className="text-[12px] leading-relaxed text-slate-700 dark:text-slate-300 whitespace-pre-wrap font-medium">
                        {explanation.text}
                      </div>

                      <div className="mt-2 pt-2 border-t border-slate-200 dark:border-white/5 flex justify-between items-center text-[9px] text-slate-500">
                        <span>{explanation.provider}</span>
                        {isSelected ? <span className="text-cyan-600 dark:text-cyan-400">正在追踪</span> : <span>{explanation.timestamp}</span>}
                      </div>
                    </div>
                  );
                })
              )}
            </div>
          </section>

          <div
            className="h-1 bg-white/5 hover:bg-cyan-500/50 cursor-row-resize transition-colors"
            onMouseDown={handleMouseDown}
          />

          <section
            style={{ height: `${100 - topSectionHeight}%` }}
            className="min-h-0 flex flex-col"
          >
            <div className="px-4 py-2.5 shrink-0 flex items-center justify-between text-[11px] uppercase tracking-[0.18em] text-slate-400">
              <span>对话助理</span>
              <button
                type="button"
                onClick={handleResetConversation}
                className="rounded border border-white/10 px-2 py-0.5 text-[10px] tracking-normal text-slate-400 hover:border-slate-500 hover:text-slate-200"
              >
                清空会话
              </button>
            </div>

            <ChatMessageList messages={chatMessages} onRetry={handleRetry} />
            <ChatComposer
              value={chatInput}
              isSending={isChatSending}
              voiceSupported={voiceCaptureSupported}
              voiceState={voiceCaptureState}
              voiceMode={activeVoiceMode}
              voiceError={voiceCaptureError}
              onChange={setChatInput}
              onSend={handleSendChat}
              onStartVoiceRecording={handleStartVoiceRecording}
              onStopVoiceRecording={handleStopVoiceRecording}
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
    case 'ALARM':
      return 3;
    case 'WARNING':
      return 2;
    case 'CAUTION':
      return 1;
    default:
      return 0;
  }
}

function getVoiceErrorMessage(error: unknown): string {
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError') {
      return '麦克风权限被拒绝';
    }

    if (error.name === 'NotFoundError' || error.name === 'DevicesNotFoundError') {
      return '未检测到可用麦克风';
    }
  }

  if (error instanceof Error && error.message.trim()) {
    return error.message;
  }

  return '录音流程失败，请重新录音';
}
