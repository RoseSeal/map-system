/**
 * AI Center Panel
 * Keeps the existing magnetic drawer behavior while combining risk logs and chat.
 * Adds lock state when typing and a draggable resizer.
 */
import { useEffect, useRef, useState, useCallback } from 'react';
import {
  useRiskStore,
  useAiCenterStore,
  selectAiCenterOpenRequestVersion,
  selectSelectedTarget,
  selectLatestLlmExplanations,
  selectReadLlmExplanations,
  selectChatMessages,
  selectChatInput,
  selectChatSessionId,
  selectIsChatSending,
  selectVoiceCaptureError,
  selectVoiceCaptureState,
  selectVoiceCaptureSupported,
  selectActiveVoiceMode,
} from '../../store';
import { CHAT_CONFIG, getRiskColor } from '../../config';
import { socketService, voiceRecorderService } from '../../services';
import type { AiCenterChatMessage } from '../../types/aiCenter';
import type { ChatMode, RiskLevel } from '../../types/schema';
import { ChatComposer } from './ChatComposer';
import { ChatMessageList } from './ChatMessageList';

const PANEL_WIDTH = 360;
const PANEL_HEIGHT = '72vh';

export function RiskExplanationPanel() {
  const allTargets = useRiskStore((state) => state.allTargets);
  const selectedTarget = useRiskStore(selectSelectedTarget);
  const selectTarget = useRiskStore((state) => state.selectTarget);

  const latestLlmExplanations = useAiCenterStore(selectLatestLlmExplanations);
  const readLlmExplanations = useAiCenterStore(selectReadLlmExplanations);
  const aiCenterOpenRequestVersion = useAiCenterStore(selectAiCenterOpenRequestVersion);
  const chatMessages = useAiCenterStore(selectChatMessages);
  const chatInput = useAiCenterStore(selectChatInput);
  const chatSessionId = useAiCenterStore(selectChatSessionId);
  const isChatSending = useAiCenterStore(selectIsChatSending);
  const voiceCaptureSupported = useAiCenterStore(selectVoiceCaptureSupported);
  const voiceCaptureState = useAiCenterStore(selectVoiceCaptureState);
  const voiceCaptureError = useAiCenterStore(selectVoiceCaptureError);
  const activeVoiceMode = useAiCenterStore(selectActiveVoiceMode);

  const isChatFocused = useAiCenterStore((state) => state.isChatFocused);

  const setChatInput = useAiCenterStore((state) => state.setChatInput);
  const appendUserChatMessage = useAiCenterStore((state) => state.appendUserChatMessage);
  const createPendingUserChatMessage = useAiCenterStore((state) => state.createPendingUserChatMessage);
  const markLlmRead = useAiCenterStore((state) => state.markLlmRead);
  const resetChatSession = useAiCenterStore((state) => state.resetChatSession);
  const setIsChatFocused = useAiCenterStore((state) => state.setIsChatFocused);
  const setVoiceCaptureSupported = useAiCenterStore((state) => state.setVoiceCaptureSupported);
  const setVoiceCaptureRecording = useAiCenterStore((state) => state.setVoiceCaptureRecording);
  const setVoiceCaptureTranscribing = useAiCenterStore((state) => state.setVoiceCaptureTranscribing);
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

  const explainedTargets = Object.values(latestLlmExplanations)
    .filter((explanation) => shouldShowRiskCard(explanation.risk_level))
    .map((explanation) => {
      const target = allTargets.find((item) => item.id === explanation.target_id);
      return target ? { target, explanation } : null;
    })
    .filter((item): item is { target: typeof allTargets[number]; explanation: typeof latestLlmExplanations[string] } => Boolean(item))
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
    const content = chatInput.trim();
    if (!content) {
      return;
    }

    const message = createPendingUserChatMessage({
      content,
      inputType: 'TEXT',
    });
    appendUserChatMessage(message);
    setChatInput('');
    socketService.sendChatMessage({
      sequenceId: chatSessionId,
      messageId: message.message_id,
      content: message.content,
    });
  };

  const handleRetry = (message: AiCenterChatMessage) => {
    if (message.role !== 'user' || message.input_type === 'SPEECH') {
      return;
    }

    const retryMessage = createPendingUserChatMessage({
      content: message.content,
      inputType: 'TEXT',
    });
    appendUserChatMessage(retryMessage);
    socketService.sendChatMessage({
      sequenceId: chatSessionId,
      messageId: retryMessage.message_id,
      content: retryMessage.content,
    });
  };

  const handleStartVoiceRecording = async () => {
    try {
      await voiceRecorderService.startRecording();
      setVoiceCaptureRecording();
    } catch (error) {
      setVoiceCaptureError(getVoiceErrorMessage(error), null);
    }
  };

  const handleStopVoiceRecording = async (mode: ChatMode) => {
    try {
      const { blob, audioFormat } = await voiceRecorderService.stopRecording();

      if (blob.size === 0) {
        throw new Error('未采集到有效语音，请重新录音');
      }

      if (blob.size > CHAT_CONFIG.MAX_AUDIO_SIZE_BYTES) {
        throw new Error('录音文件超过 10 MiB，请缩短录音时长');
      }

      const audioData = await voiceRecorderService.blobToBase64(blob);
      const message = createPendingUserChatMessage({
        content: '语音消息转录中...',
        inputType: 'SPEECH',
        chatMode: mode,
        audioFormat,
      });

      if (mode === 'direct') {
        appendUserChatMessage(message);
      }

      setVoiceCaptureTranscribing(message.message_id, mode);

      const didSend = socketService.sendSpeechChatMessage({
        sequenceId: chatSessionId,
        messageId: message.message_id,
        audioData,
        audioFormat,
        mode,
      });

      if (!didSend) {
        setVoiceCaptureError('语音消息发送失败，请重新录音', message.message_id);
      }
    } catch (error) {
      setVoiceCaptureError(getVoiceErrorMessage(error), null);
    }
  };

  const handleResetChatSession = () => {
    voiceRecorderService.cancelRecording();
    resetVoiceCapture();
    resetChatSession();
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
        className="h-full bg-slate-950/85 backdrop-blur-xl border-l border-white/10 shadow-2xl rounded-l-2xl flex flex-col pointer-events-auto overflow-hidden relative"
        style={{ width: `${PANEL_WIDTH}px` }}
      >
        <div className="px-4 py-3 border-b border-white/10 shrink-0 bg-gradient-to-r from-transparent to-cyan-950/20">
          <div className="flex items-center justify-between gap-2">
            <h2 className="text-sm font-bold tracking-wide text-slate-100 flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-cyan-400 animate-pulse"></span>
              AI 助理中心
            </h2>
            <span className="text-[10px] bg-slate-800 text-slate-400 px-1.5 py-0.5 rounded font-mono">
              {explainedTargets.length} / {chatMessages.length}
            </span>
          </div>
        </div>

        <div className="flex-1 min-h-0 flex flex-col">
          <section
            style={{ height: `${topSectionHeight}%` }}
            className="min-h-0 border-b border-white/10 flex flex-col"
          >
            <div className="px-4 py-2.5 shrink-0 flex items-center justify-between text-[11px] uppercase tracking-[0.18em] text-slate-400">
              <span>态势监控日志</span>
              <span>{explainedTargets.length} 个风险目标</span>
            </div>

            <div className="flex-1 min-h-0 overflow-y-auto p-3 space-y-3 scrollbar-thin relative">
              {explainedTargets.length === 0 ? (
                <div className="absolute inset-0 flex items-center justify-center px-6 text-center text-xs text-slate-500">
                  当前航区暂无 AI 风险评估
                </div>
              ) : (
                explainedTargets.map(({ target, explanation }) => {
                  const isSelected = selectedTarget?.id === target.id;
                  const riskColor = getRiskColor(explanation.risk_level);
                  const riskHex = `rgb(${riskColor.join(',')})`;
                  const isRead = readLlmExplanations[target.id];

                  return (
                    <div
                      key={target.id}
                      onClick={() => {
                        selectTarget(target.id);
                        markLlmRead(target.id);
                      }}
                      className={`p-3 rounded-lg border transition-all duration-200 cursor-pointer ${
                        isSelected
                          ? 'border-cyan-500/50 bg-cyan-950/30 shadow-[inset_0_0_20px_rgba(6,182,212,0.05)]'
                          : 'border-white/5 bg-slate-900/50 hover:bg-slate-800/80'
                      }`}
                    >
                      <div className="flex justify-between items-center mb-2 gap-2">
                        <div className="flex items-center gap-2 min-w-0">
                          <span className="font-mono text-xs text-slate-200 truncate">ID: {target.id}</span>
                          {!isRead && <span className="h-2 w-2 rounded-full bg-cyan-400 shadow-[0_0_10px_rgba(34,211,238,0.8)]"></span>}
                        </div>
                        <span
                          className="text-[9px] font-bold px-1.5 py-0.5 rounded uppercase"
                          style={{ color: riskHex, backgroundColor: `rgba(${riskColor.join(',')}, 0.15)` }}
                        >
                          {explanation.risk_level}
                        </span>
                      </div>

                      <div className="text-[12px] leading-relaxed text-slate-300 whitespace-pre-wrap font-medium">
                        {explanation?.text}
                      </div>

                      <div className="mt-2 pt-2 border-t border-white/5 flex justify-between items-center text-[9px] text-slate-500">
                        <span>{explanation?.source || 'LLM'}</span>
                        {isSelected ? <span className="text-cyan-400">正在追踪</span> : <span>{isRead ? '已读' : '未读'}</span>}
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
                onClick={handleResetChatSession}
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
