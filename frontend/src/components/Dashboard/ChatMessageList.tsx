import { useEffect, useRef } from 'react';
import type { AiCenterChatMessage } from '../../types/aiCenter';

interface ChatMessageListProps {
  messages: AiCenterChatMessage[];
  onRetry: (message: AiCenterChatMessage) => void;
}

export function ChatMessageList({ messages, onRetry }: ChatMessageListProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return;
    }

    container.scrollTop = container.scrollHeight;
  }, [messages]);

  if (messages.length === 0) {
    return (
      <div className="flex-1 min-h-0 flex items-center justify-center px-4 text-center text-xs text-slate-500">
        当前暂无对话。可以询问高风险目标、避碰建议或当前态势判断。
      </div>
    );
  }

  return (
    <div ref={containerRef} className="flex-1 min-h-0 overflow-y-auto px-3 py-3 space-y-3 scrollbar-thin">
      {messages.map((message) => {
        const isUser = message.role === 'user';
        const isSpeechMessage = message.input_type === 'SPEECH';
        const showError = isUser && message.status === 'error' && message.error_message;
        const showRetry = showError && message.input_type !== 'SPEECH';
        const showInterruptPlaceholder = isUser && (message.status === 'pending' || message.status === 'sent');

        return (
          <div
            key={message.message_id}
            className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}
          >
            <div className={`max-w-[88%] space-y-1 ${isUser ? 'items-end' : 'items-start'}`}>
              <div
                className={[
                  'rounded-2xl px-3 py-2 text-sm leading-6 shadow-sm border',
                  isUser
                    ? showError
                      ? 'bg-red-500/10 border-red-500/30 text-red-100'
                      : 'bg-cyan-500/15 border-cyan-500/25 text-cyan-50'
                    : 'bg-slate-900/80 border-white/10 text-slate-100',
                ].join(' ')}
              >
                {isSpeechMessage && (
                  <div className="mb-1 flex items-center justify-end gap-1.5 text-[10px] uppercase tracking-[0.14em] text-cyan-200/70">
                    <span>VOICE</span>
                    {message.chat_mode && <span>{message.chat_mode}</span>}
                  </div>
                )}
                <div className="whitespace-pre-wrap break-words">{message.content}</div>
              </div>

              <div className={`flex items-center gap-2 text-[10px] ${isUser ? 'justify-end text-slate-500' : 'justify-start text-slate-500'}`}>
                <span>{isUser ? '我方' : 'AI 助手'}</span>
                <span>{formatStatus(message)}</span>
              </div>

              {showInterruptPlaceholder && (
                <button
                  type="button"
                  disabled
                  className="inline-flex items-center gap-2 rounded-full border border-cyan-500/15 bg-slate-900/70 px-2.5 py-1 text-[10px] text-slate-400 cursor-not-allowed"
                  title="主动中断能力预留，待后端协议支持"
                >
                  <span className="h-3 w-3 rounded-full border-2 border-cyan-400/60 border-t-transparent animate-spin" />
                  <span>处理中</span>
                </button>
              )}

              {showError && (
                <div className="flex items-center gap-2 text-[11px] text-red-300">
                  <span>{message.error_message}</span>
                  {showRetry ? (
                    <button
                      type="button"
                      onClick={() => onRetry(message)}
                      className="rounded border border-red-500/30 px-2 py-0.5 text-red-200 hover:bg-red-500/10"
                    >
                      重试
                    </button>
                  ) : (
                    <span className="text-red-200/80">请重新录音</span>
                  )}
                </div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function formatStatus(message: AiCenterChatMessage): string {
  if (message.role === 'assistant') {
    return message.source || '回复完成';
  }

  if (message.input_type === 'SPEECH') {
    switch (message.status) {
      case 'pending':
        return '语音处理中';
      case 'error':
        return '发送失败';
      case 'replied':
        return '已回复';
      default:
        return '已发送';
    }
  }

  switch (message.status) {
    case 'pending':
      return '等待回复';
    case 'error':
      return '发送失败';
    case 'replied':
      return '已回复';
    default:
      return '已发送';
  }
}
