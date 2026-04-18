import { type KeyboardEvent, type MouseEvent, useEffect, useState } from 'react';
import type { VoiceCaptureState } from '../../store/useAiCenterStore';
import type { SpeechMode } from '../../types/schema';

interface SelectedTargetChip {
  id: string;
  riskLevel: string;
}

interface ChatComposerProps {
  value: string;
  disabled?: boolean;
  isSending?: boolean;
  voiceSupported: boolean;
  voiceState: VoiceCaptureState;
  voiceMode?: SpeechMode | null;
  voiceError?: string | null;
  sendError?: string | null;
  selectedTargets?: SelectedTargetChip[];
  droppedTargetNotices?: string[];
  onDeselectTarget?: (id: string) => void;
  onClearDroppedNotices?: () => void;
  onChange: (value: string) => void;
  onSend: () => void;
  onStartVoiceRecording: () => void;
  onStopVoiceRecording: (mode: SpeechMode) => void;
  onCancelVoiceRecording?: () => void;
  onFocus?: () => void;
  onBlur?: () => void;
}

export function ChatComposer({
  value,
  disabled = false,
  isSending = false,
  voiceSupported,
  voiceState,
  voiceMode = null,
  voiceError,
  sendError,
  selectedTargets = [],
  droppedTargetNotices = [],
  onDeselectTarget,
  onClearDroppedNotices,
  onChange,
  onSend,
  onStartVoiceRecording,
  onStopVoiceRecording,
  onCancelVoiceRecording,
  onFocus,
  onBlur,
}: ChatComposerProps) {
  const [visibleDropped, setVisibleDropped] = useState<string[]>([]);

  useEffect(() => {
    if (droppedTargetNotices.length === 0) return;
    setVisibleDropped(droppedTargetNotices);
    const timer = setTimeout(() => {
      setVisibleDropped([]);
      onClearDroppedNotices?.();
    }, 3000);
    return () => clearTimeout(timer);
  }, [droppedTargetNotices, onClearDroppedNotices]);

  const hasText = Boolean(value.trim());
  const canSend = !disabled && hasText;
  const canStartVoice = voiceSupported && !disabled && !hasText && voiceState !== 'transcribing';
  const inputDisabled = disabled || voiceState === 'recording' || voiceState === 'transcribing';

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      if (canSend) {
        onSend();
      }
    }
  };

  const handleMouseDown = (event: MouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
  };

  const handlePrimaryAction = () => {
    if (voiceState === 'recording') {
      return;
    }

    if (hasText) {
      onSend();
      return;
    }

    onStartVoiceRecording();
  };

  const primaryDisabled = voiceState === 'recording'
    ? true
    : hasText
      ? !canSend
      : !canStartVoice;

  return (
    <div
      className="shrink-0 p-3 space-y-2"
      style={{ borderTop: '0.5px solid color-mix(in oklch, var(--ink-500) 12%, transparent)' }}
    >
      {selectedTargets.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {selectedTargets.map((target) => (
            <span
              key={target.id}
              className="inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[10px]"
              style={{
                border: '0.5px solid color-mix(in oklch, var(--ink-500) 15%, transparent)',
                background: 'color-mix(in oklch, var(--ink-500) 8%, transparent)',
                color: 'var(--ink-700)',
              }}
            >
              <span
                className="inline-block h-1.5 w-1.5 rounded-full"
                style={{ backgroundColor: getRiskChipColor(target.riskLevel) }}
              />
              <span className="font-mono">{target.id}</span>
              {onDeselectTarget && (
                <button
                  type="button"
                  onClick={() => onDeselectTarget(target.id)}
                  style={{ marginLeft: 2, color: 'var(--ink-500)', background: 'none', border: 'none', cursor: 'pointer' }}
                >
                  &times;
                </button>
              )}
            </span>
          ))}
        </div>
      )}
      {visibleDropped.length > 0 && (
        <div className="anim-soft-pulse text-[10px]" style={{ color: 'var(--risk-warning)' }}>
          目标 {visibleDropped.join('、')} 已失去跟踪，已自动移除
        </div>
      )}
      <textarea
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={handleKeyDown}
        onFocus={onFocus}
        onBlur={onBlur}
        disabled={inputDisabled}
        placeholder="向 AI 助手提问，例如：评估当前水域风险..."
        rows={3}
        className="w-full resize-none rounded-lg px-3 py-2 text-sm outline-none transition-colors placeholder:opacity-40"
        style={{
          border: `0.5px solid color-mix(in oklch, var(--ink-500) ${inputDisabled ? '8' : '15'}%, transparent)`,
          background: inputDisabled
            ? 'color-mix(in oklch, var(--ink-500) 5%, transparent)'
            : 'color-mix(in oklch, var(--ink-100) 45%, transparent)',
          color: inputDisabled ? 'var(--ink-500)' : 'var(--ink-900)',
          cursor: inputDisabled ? 'not-allowed' : 'auto',
        }}
      />

      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1 space-y-2">
          {voiceState === 'recording' ? (
            <>
              <div
                className="flex items-center gap-2"
                style={{
                  borderRadius: 8,
                  border: '0.5px solid color-mix(in oklch, var(--risk-safe) 30%, transparent)',
                  background: 'color-mix(in oklch, var(--risk-safe) 8%, transparent)',
                  padding: '8px 10px',
                }}
              >
                <span
                  className="anim-soft-pulse"
                  style={{ display: 'inline-block', width: 7, height: 7, borderRadius: 999, background: 'var(--risk-safe)' }}
                />
                <span className="text-[11px]" style={{ color: 'var(--risk-safe)' }}>
                  录音中，结束时请选择发送模式
                </span>
              </div>

              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  onMouseDown={handleMouseDown}
                  onClick={() => onStopVoiceRecording('direct')}
                  className="rounded-md text-[11px] font-medium transition-opacity hover:opacity-80"
                  style={{
                    padding: '6px 10px',
                    border: '0.5px solid color-mix(in oklch, var(--risk-safe) 40%, transparent)',
                    background: 'color-mix(in oklch, var(--risk-safe) 12%, transparent)',
                    color: 'var(--risk-safe)',
                    cursor: 'pointer',
                  }}
                >
                  停止并直发
                </button>
                <button
                  type="button"
                  onMouseDown={handleMouseDown}
                  onClick={() => onStopVoiceRecording('preview')}
                  className="rounded-md text-[11px] font-medium transition-opacity hover:opacity-80"
                  style={{
                    padding: '6px 10px',
                    border: '0.5px solid color-mix(in oklch, var(--accent) 40%, transparent)',
                    background: 'color-mix(in oklch, var(--accent) 12%, transparent)',
                    color: 'var(--accent)',
                    cursor: 'pointer',
                  }}
                >
                  停止并预览
                </button>
                {onCancelVoiceRecording && (
                  <button
                    type="button"
                    onMouseDown={handleMouseDown}
                    onClick={onCancelVoiceRecording}
                    className="rounded-md text-[11px] font-medium transition-opacity hover:opacity-70"
                    style={{
                      padding: '6px 10px',
                      border: '0.5px solid color-mix(in oklch, var(--ink-500) 20%, transparent)',
                      background: 'transparent',
                      color: 'var(--ink-500)',
                      cursor: 'pointer',
                    }}
                  >
                    取消
                  </button>
                )}
              </div>
            </>
          ) : (
            <div className="flex items-center gap-2 min-h-[20px]">
              <span className={statusIndicatorClassName(voiceState)} />
              <span className="text-[11px] truncate" style={{ color: 'var(--ink-500)' }}>
                {sendError || voiceError || getVoiceStatusText(voiceSupported, voiceState, hasText, voiceMode)}
              </span>
            </div>
          )}
        </div>

        <button
          type="button"
          onMouseDown={handleMouseDown}
          onClick={handlePrimaryAction}
          disabled={primaryDisabled}
          className="shrink-0 rounded-md text-xs font-medium transition-opacity"
          style={{
            padding: '6px 12px',
            ...(primaryDisabled
              ? {
                  border: '0.5px solid color-mix(in oklch, var(--ink-500) 10%, transparent)',
                  background: 'color-mix(in oklch, var(--ink-500) 5%, transparent)',
                  color: 'var(--ink-300)',
                  cursor: 'not-allowed',
                }
              : hasText
                ? {
                    border: '0.5px solid color-mix(in oklch, var(--accent) 40%, transparent)',
                    background: 'color-mix(in oklch, var(--accent) 12%, transparent)',
                    color: 'var(--accent)',
                    cursor: 'pointer',
                  }
                : {
                    border: '0.5px solid color-mix(in oklch, var(--ink-500) 20%, transparent)',
                    background: 'color-mix(in oklch, var(--ink-500) 8%, transparent)',
                    color: 'var(--ink-700)',
                    cursor: 'pointer',
                  }),
          }}
        >
          {voiceState === 'transcribing'
            ? '转录中...'
            : hasText
              ? (isSending ? '发送中...' : '发送')
              : voiceState === 'sent'
                ? '开始录音'
                : voiceState === 'error'
                  ? '重新录音'
                  : '开始录音'}
        </button>
      </div>
    </div>
  );
}

function getVoiceStatusText(
  voiceSupported: boolean,
  voiceState: VoiceCaptureState,
  hasText: boolean,
  voiceMode: SpeechMode | null,
): string {
  if (!voiceSupported) {
    return '当前浏览器不支持录音';
  }

  if (hasText) {
    if (voiceState === 'sent' && voiceMode === 'preview') {
      return '转录结果已写入输入框，可直接发送';
    }
    return '输入框已有文本，录音已禁用';
  }

  switch (voiceState) {
    case 'recording':
      return '录音中';
    case 'transcribing':
      return '语音已发送，正在转录';
    case 'sent':
      return voiceMode === 'preview'
        ? '预览转录已写入输入框，可直接发送'
        : '语音已直发，等待回复';
    case 'error':
      return '录音流程失败，请重新录音';
    default:
      return '输入框为空时可直接开始录音';
  }
}

function getRiskChipColor(riskLevel: string): string {
  switch (riskLevel) {
    case 'ALARM': return '#ef4444';
    case 'WARNING': return '#f59e0b';
    case 'CAUTION': return '#3b82f6';
    case 'SAFE': return '#22c55e';
    default: return '#94a3b8';
  }
}

function statusIndicatorClassName(voiceState: VoiceCaptureState): string {
  switch (voiceState) {
    case 'transcribing':
      return 'inline-block h-3 w-3 rounded-full border-2 border-cyan-400/70 border-t-transparent animate-spin';
    case 'sent':
      return 'inline-block h-2 w-2 rounded-full bg-emerald-400';
    case 'error':
      return 'inline-block h-2 w-2 rounded-full bg-red-400';
    default:
      return 'inline-block h-2 w-2 rounded-full bg-slate-500';
  }
}
