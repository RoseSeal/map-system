import React, { useEffect, useRef } from 'react';
import type { AiCenterChatMessage } from '../../types/aiCenter';
import type { AgentStepPayload } from '../../types/schema';

interface ChatMessageListProps {
  messages: AiCenterChatMessage[];
  onRetry: (message: AiCenterChatMessage) => void;
  editingMessageEventId?: string | null;
  editingDraft?: string;
  editingSubmitPending?: boolean;
  editingSubmitError?: string | null;
  onStartEditingLastUserMessage?: () => void;
  onUpdateEditingDraft?: (value: string) => void;
  onConfirmEditingLastUserMessage?: () => void;
  onCancelEditingLastUserMessage?: () => void;
  onClearEditingSubmitError?: () => void;
  agentStepsByReplyToEventId?: Record<string, AgentStepPayload[]>;
}

export function ChatMessageList({
  messages,
  onRetry,
  editingMessageEventId = null,
  editingDraft = '',
  editingSubmitPending = false,
  editingSubmitError = null,
  onStartEditingLastUserMessage,
  onUpdateEditingDraft,
  onConfirmEditingLastUserMessage,
  onCancelEditingLastUserMessage,
  onClearEditingSubmitError,
  agentStepsByReplyToEventId = {},
}: ChatMessageListProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const editableMessage = getEditableLastUserMessage(messages);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return;
    }

    container.scrollTop = container.scrollHeight;
  }, [messages]);

  if (messages.length === 0) {
    return (
      <div className="flex-1 min-h-0 flex items-center justify-center px-4 text-center text-xs text-slate-500 transition-colors duration-300">
        当前暂无对话。可以询问高风险目标、避碰建议或当前态势判断。
      </div>
    );
  }

  return (
    <div ref={containerRef} className="flex-1 min-h-0 overflow-y-auto px-3 py-3 space-y-4 scrollbar-apple transition-colors duration-300">
      {messages.map((message) => {
        const isUser = message.role === 'user';
        const isSpeechMessage = message.request_type === 'SPEECH' || message.message_type === 'speech_transcript';
        const showError = isUser && message.status === 'error' && message.error_message;
        const showRetry = showError && message.request_type !== 'SPEECH';
        const showInterruptPlaceholder = isUser && message.status === 'pending';
        const agentSteps = isUser ? (agentStepsByReplyToEventId[message.event_id] ?? []) : [];
        const isEditing = isUser && editingMessageEventId === message.event_id;
        const showEditButton = Boolean(
          isUser
            && editableMessage
            && editableMessage.event_id === message.event_id
            && !editingMessageEventId
            && !editingSubmitPending
            && onStartEditingLastUserMessage,
        );

        return (
          <div
            key={message.event_id}
            className={`flex flex-col ${isUser ? 'items-end' : 'items-start'}`}
          >
            <div className="max-w-[92%] relative group">
              {isEditing ? (
                /* 编辑状态：不使用气泡包裹，直接展示输入框和按钮 */
                <div className="w-full min-w-[280px] space-y-3 py-1">
                  <textarea
                    value={editingDraft}
                    onChange={(event) => onUpdateEditingDraft?.(event.target.value)}
                    rows={4}
                    disabled={editingSubmitPending}
                    autoFocus
                    className="w-full resize-none rounded-xl border border-cyan-500/30 bg-white/60 dark:bg-slate-900/60 backdrop-blur-sm px-3 py-2.5 text-sm text-slate-800 dark:text-slate-100 outline-none ring-1 ring-transparent focus:ring-cyan-500/40 transition-all disabled:cursor-not-allowed disabled:opacity-70 shadow-sm"
                  />
                  <div className="flex items-center justify-end gap-4">
                    <button
                      type="button"
                      onClick={onCancelEditingLastUserMessage}
                      disabled={editingSubmitPending}
                      className="text-[11px] font-bold text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 transition-colors disabled:opacity-50"
                    >
                      取消
                    </button>
                    <button
                      type="button"
                      onClick={onConfirmEditingLastUserMessage}
                      disabled={editingSubmitPending}
                      className="rounded-lg bg-cyan-600 px-4 py-1.5 text-[11px] font-bold text-white shadow-md hover:bg-cyan-500 active:scale-95 transition-all disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      重新发送
                    </button>
                  </div>
                  {editingSubmitError && (
                    <div className="flex items-center justify-between gap-2 rounded-lg border border-red-500/20 bg-red-50/80 px-3 py-2 text-[10px] text-red-600 dark:bg-red-500/10 dark:text-red-200">
                      <span>{editingSubmitError}</span>
                      <button
                        type="button"
                        onClick={onClearEditingSubmitError}
                        className="font-bold opacity-60 hover:opacity-100"
                      >
                        ✕
                      </button>
                    </div>
                  )}
                </div>
              ) : (
                /* 非编辑状态：正常气泡展示 */
                <>
                  <div
                    className={[
                      'rounded-2xl px-3.5 py-2.5 text-sm leading-relaxed shadow-sm border transition-all duration-300',
                      isUser
                        ? showError
                          ? 'bg-red-50 dark:bg-red-500/10 border-red-200 dark:border-red-500/30 text-red-600 dark:text-red-100'
                          : 'bg-cyan-50/80 dark:bg-cyan-500/10 border-cyan-100 dark:border-cyan-500/20 text-cyan-900 dark:text-cyan-50'
                        : 'bg-white dark:bg-slate-900/60 border-slate-100 dark:border-white/5 text-slate-800 dark:text-slate-100',
                    ].join(' ')}
                  >
                    {isSpeechMessage && (
                      <div className="mb-1.5 flex items-center gap-1.5 text-[9px] uppercase tracking-widest text-cyan-600 dark:text-cyan-400/70 font-bold">
                        <span className="w-1 h-1 rounded-full bg-current animate-pulse"></span>
                        <span>VOICE TRANSCRIPT</span>
                      </div>
                    )}
                    <div className="whitespace-pre-wrap break-words">{message.content}</div>
                  </div>

                  {/* 画笔图标：绝对定位在气泡右下角外部或内边缘 */}
                  {showEditButton && (
                    <button
                      type="button"
                      onClick={onStartEditingLastUserMessage}
                      className="absolute -right-2 -bottom-2 p-1.5 bg-white dark:bg-slate-800 rounded-full shadow-md border border-slate-100 dark:border-white/10 text-slate-400 hover:text-cyan-600 dark:hover:text-cyan-400 hover:scale-110 transition-all opacity-0 group-hover:opacity-100 z-10"
                      title="重新编辑并发送"
                    >
                      <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" />
                      </svg>
                    </button>
                  )}
                </>
              )}
            </div>

            <div className={`mt-1.5 flex items-center gap-2 text-[10px] ${isUser ? 'justify-end pr-1' : 'justify-start pl-1'} text-slate-400 font-medium`}>
              <span>{isUser ? '我方' : 'AI 助手'}</span>
              <span className="w-1 h-1 rounded-full bg-slate-300 dark:bg-slate-700"></span>
              <span>{formatStatus(message)}</span>
            </div>

            {agentSteps.length > 0 && (
              <div className="mt-2 space-y-1">
                {agentSteps.map((step) => (
                  <div
                    key={step.event_id}
                    className="flex items-center gap-2 rounded-full border px-3 py-1 text-[10px]"
                    style={agentStepStyle(step.status)}
                  >
                    {step.status === 'RUNNING' && (
                      <span className="h-2 w-2 rounded-full border-2 border-current border-t-transparent animate-spin" />
                    )}
                    {(step.status === 'SUCCEEDED' || step.status === 'FINALIZING') && (
                      <span className="h-2 w-2 rounded-full bg-current" />
                    )}
                    {step.status === 'FAILED' && (
                      <span className="h-2 w-2 rounded-full bg-current" />
                    )}
                    <span className="font-medium">{step.message}</span>
                  </div>
                ))}
              </div>
            )}
            {showInterruptPlaceholder && agentSteps.length === 0 && (
              <div className="mt-2 flex items-center gap-2 rounded-full border border-cyan-500/10 bg-cyan-500/5 px-3 py-1 text-[10px] text-cyan-600 dark:text-cyan-400">
                <span className="h-2 w-2 rounded-full border-2 border-current border-t-transparent animate-spin" />
                <span className="font-bold tracking-tight uppercase">Processing</span>
              </div>
            )}

            {showError && (
              <div className="mt-2 flex items-center gap-2 text-[10px] font-bold text-red-500 dark:text-red-400 bg-red-500/5 px-3 py-1 rounded-full border border-red-500/10">
                <span>{message.error_message}</span>
                {showRetry ? (
                  <button
                    type="button"
                    onClick={() => onRetry(message)}
                    className="ml-1 text-red-600 dark:text-red-300 underline underline-offset-2 hover:text-red-500"
                  >
                    重试
                  </button>
                ) : (
                  <span className="opacity-60">请重新录音</span>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function getEditableLastUserMessage(messages: AiCenterChatMessage[]): AiCenterChatMessage | null {
  if (messages.length < 2) {
    return null;
  }

  const userMessage = messages[messages.length - 2];
  const assistantMessage = messages[messages.length - 1];
  if (userMessage.role !== 'user' || userMessage.request_type !== 'CHAT' || assistantMessage.role !== 'assistant') {
    return null;
  }

  return userMessage;
}

function agentStepStyle(status: AgentStepPayload['status']): React.CSSProperties {
  switch (status) {
    case 'RUNNING':
      return { borderColor: 'rgba(59,130,246,0.2)', background: 'rgba(59,130,246,0.05)', color: 'rgb(59,130,246)' };
    case 'SUCCEEDED':
      return { borderColor: 'rgba(16,185,129,0.2)', background: 'rgba(16,185,129,0.05)', color: 'rgb(16,185,129)' };
    case 'FAILED':
      return { borderColor: 'rgba(239,68,68,0.2)', background: 'rgba(239,68,68,0.05)', color: 'rgb(239,68,68)' };
    case 'FINALIZING':
      return { borderColor: 'rgba(148,163,184,0.2)', background: 'rgba(148,163,184,0.05)', color: 'rgb(100,116,139)' };
  }
}

function formatStatus(message: AiCenterChatMessage): string {
  if (message.role === 'assistant') {
    return message.provider || '回复完成';
  }

  if (message.request_type === 'SPEECH' || message.message_type === 'speech_transcript') {
    switch (message.status) {
      case 'pending':
        return '正在转录';
      case 'sent':
        return message.speech_mode === 'preview' ? '待确认' : '已发送';
      case 'error':
        return '发送失败';
      case 'replied':
        return '已完成';
      default:
        return '已发送';
    }
  }

  switch (message.status) {
    case 'pending':
      return '正在处理';
    case 'error':
      return '发送失败';
    case 'replied':
      return '已完成';
    default:
      return '已发送';
  }
}
