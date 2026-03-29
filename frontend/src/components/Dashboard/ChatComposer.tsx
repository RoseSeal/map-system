import type { KeyboardEvent, MouseEvent } from 'react';
import type { VoiceCaptureState } from '../../store/useAiCenterStore';
import type { ChatMode } from '../../types/schema';

interface ChatComposerProps {
  value: string;
  disabled?: boolean;
  isSending?: boolean;
  voiceSupported: boolean;
  voiceState: VoiceCaptureState;
  voiceError?: string | null;
  onChange: (value: string) => void;
  onSend: () => void;
  onStartVoiceRecording: () => void;
  onStopVoiceRecording: (mode: ChatMode) => void;
  onFocus?: () => void;
  onBlur?: () => void;
}

export function ChatComposer({
  value,
  disabled = false,
  isSending = false,
  voiceSupported,
  voiceState,
  voiceError,
  onChange,
  onSend,
  onStartVoiceRecording,
  onStopVoiceRecording,
  onFocus,
  onBlur,
}: ChatComposerProps) {
  const canSend = !disabled && Boolean(value.trim());
  const canStartVoice = voiceSupported && !disabled && voiceState !== 'recording' && voiceState !== 'transcribing';

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

  return (
    <div className="shrink-0 border-t border-white/10 bg-slate-950/80 p-3 space-y-2">
      <textarea
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={handleKeyDown}
        onFocus={onFocus}
        onBlur={onBlur}
        placeholder="向 AI 助手提问，例如：评估当前水域风险..."
        rows={3}
        className="w-full resize-none rounded-lg border border-white/10 bg-slate-900/80 px-3 py-2 text-sm text-slate-100 outline-none transition-colors placeholder:text-slate-500 focus:border-cyan-500/50"
      />

      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0 flex-1 space-y-2">
          {voiceState === 'recording' ? (
            <div className="flex items-center gap-2 rounded-lg border border-red-500/20 bg-red-500/10 px-2.5 py-2">
              <span className="h-2 w-2 rounded-full bg-red-400 animate-pulse" />
              <span className="text-[11px] text-red-100">录音中，结束时请选择发送模式</span>
            </div>
          ) : (
            <div className="flex items-center gap-2 text-[11px] text-slate-400 min-h-[20px]">
              <span className={statusIndicatorClassName(voiceState)} />
              <span className="truncate">
                {voiceError || getVoiceStatusText(voiceSupported, voiceState)}
              </span>
            </div>
          )}

          <div className="flex flex-wrap items-center gap-2">
            {voiceState === 'recording' ? (
              <>
                <button
                  type="button"
                  onMouseDown={handleMouseDown}
                  onClick={() => onStopVoiceRecording('direct')}
                  className="px-2.5 py-1.5 rounded-md border border-red-500/40 bg-red-500/15 text-[11px] font-medium text-red-100 hover:bg-red-500/25"
                >
                  停止并直发
                </button>
                <button
                  type="button"
                  onMouseDown={handleMouseDown}
                  disabled
                  className="px-2.5 py-1.5 rounded-md border border-white/5 bg-slate-900/40 text-[11px] text-slate-600 cursor-not-allowed"
                  title="preview 模式待后端支持"
                >
                  预览转录（待支持）
                </button>
              </>
            ) : (
              <button
                type="button"
                onMouseDown={handleMouseDown}
                onClick={onStartVoiceRecording}
                disabled={!canStartVoice}
                className={[
                  'px-2.5 py-1.5 rounded-md border text-[11px] font-medium transition-colors',
                  canStartVoice
                    ? 'border-white/10 bg-slate-900/70 text-slate-200 hover:border-cyan-500/40 hover:text-cyan-200'
                    : 'border-white/5 bg-slate-900/40 text-slate-600 cursor-not-allowed',
                ].join(' ')}
              >
                {voiceState === 'sent' ? '再次录音' : voiceState === 'error' ? '重新录音' : '开始录音'}
              </button>
            )}
          </div>
        </div>

        <button
          type="button"
          onMouseDown={handleMouseDown}
          onClick={onSend}
          disabled={!canSend}
          className={[
            'shrink-0 px-3 py-1.5 rounded-md text-xs font-medium border transition-colors',
            canSend
              ? 'border-cyan-500/40 bg-cyan-500/15 text-cyan-200 hover:bg-cyan-500/25'
              : 'border-white/5 bg-slate-900/40 text-slate-600 cursor-not-allowed',
          ].join(' ')}
        >
          {isSending ? '发送中...' : '发送'}
        </button>
      </div>
    </div>
  );
}

function getVoiceStatusText(voiceSupported: boolean, voiceState: VoiceCaptureState): string {
  if (!voiceSupported) {
    return '当前浏览器不支持录音';
  }

  switch (voiceState) {
    case 'recording':
      return '录音中';
    case 'transcribing':
      return '语音已发送，正在转录';
    case 'sent':
      return '语音发送完成';
    case 'error':
      return '录音流程失败，请重新录音';
    default:
      return '语音模式支持 direct，preview 待后端支持';
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
