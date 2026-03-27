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
        const showError = isUser && message.status === 'error' && message.error_message;

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
                <div className="whitespace-pre-wrap break-words">{message.content}</div>
              </div>

              <div className={`flex items-center gap-2 text-[10px] ${isUser ? 'justify-end text-slate-500' : 'justify-start text-slate-500'}`}>
                <span>{isUser ? '我方' : 'AI 助手'}</span>
                <span>{formatStatus(message)}</span>
              </div>

              {showError && (
                <div className="flex items-center gap-2 text-[11px] text-red-300">
                  <span>{message.error_message}</span>
                  <button
                    type="button"
                    onClick={() => onRetry(message)}
                    className="rounded border border-red-500/30 px-2 py-0.5 text-red-200 hover:bg-red-500/10"
                  >
                    重试
                  </button>
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
