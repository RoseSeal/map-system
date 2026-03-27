import type { KeyboardEvent } from 'react';

interface ChatComposerProps {
  value: string;
  disabled?: boolean;
  isSending?: boolean;
  onChange: (value: string) => void;
  onSend: () => void;
  onFocus?: () => void;
  onBlur?: () => void;
}

export function ChatComposer({
  value,
  disabled = false,
  isSending = false,
  onChange,
  onSend,
  onFocus,
  onBlur,
}: ChatComposerProps) {
  const canSend = !disabled && Boolean(value.trim());

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      if (canSend) {
        onSend();
      }
    }
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

      <div className="flex items-center justify-between gap-2">
        <button
          type="button"
          disabled
          className="px-2.5 py-1.5 rounded-md border border-white/5 bg-slate-900/50 text-[11px] text-slate-500 cursor-not-allowed"
          title="语音输入能力预留，当前版本仅支持文本聊天"
        >
          开始说话（预留）
        </button>

        <button
          type="button"
          onClick={onSend}
          disabled={!canSend}
          className={[
            'px-3 py-1.5 rounded-md text-xs font-medium border transition-colors',
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