import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ChatMessageList } from './ChatMessageList';
import type { AiCenterChatMessage } from '../../types/aiCenter';

const messages: AiCenterChatMessage[] = [
  {
    event_id: 'user-event-1',
    conversation_id: 'conversation-1',
    role: 'user',
    request_type: 'CHAT',
    content: 'Original question',
    status: 'replied',
    timestamp: '2026-04-15T12:00:00.000Z',
    message_type: 'chat_user',
  },
  {
    event_id: 'assistant-event-1',
    conversation_id: 'conversation-1',
    role: 'assistant',
    content: 'Original answer',
    status: 'sent',
    reply_to_event_id: 'user-event-1',
    provider: 'gemini',
    timestamp: '2026-04-15T12:01:00.000Z',
    message_type: 'chat_reply',
  },
];

describe('ChatMessageList', () => {
  it('shows an edit button only for the last complete chat turn', () => {
    const onStartEditingLastUserMessage = vi.fn();

    render(
      <ChatMessageList
        messages={messages}
        onRetry={vi.fn()}
        onStartEditingLastUserMessage={onStartEditingLastUserMessage}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '重新编辑并发送' }));

    expect(onStartEditingLastUserMessage).toHaveBeenCalledTimes(1);
  });

  it('renders the inline editor and wires confirm, cancel, update and error clear actions', () => {
    const onUpdateEditingDraft = vi.fn();
    const onConfirmEditingLastUserMessage = vi.fn();
    const onCancelEditingLastUserMessage = vi.fn();
    const onClearEditingSubmitError = vi.fn();

    render(
      <ChatMessageList
        messages={messages}
        onRetry={vi.fn()}
        editingMessageEventId="user-event-1"
        editingDraft="Edited question"
        editingSubmitError="Edit failed"
        onUpdateEditingDraft={onUpdateEditingDraft}
        onConfirmEditingLastUserMessage={onConfirmEditingLastUserMessage}
        onCancelEditingLastUserMessage={onCancelEditingLastUserMessage}
        onClearEditingSubmitError={onClearEditingSubmitError}
      />,
    );

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Edited again' } });
    fireEvent.click(screen.getByRole('button', { name: '重新发送' }));
    fireEvent.click(screen.getByRole('button', { name: '取消' }));
    fireEvent.click(screen.getByRole('button', { name: '✕' }));

    expect(onUpdateEditingDraft).toHaveBeenCalledWith('Edited again');
    expect(onConfirmEditingLastUserMessage).toHaveBeenCalledTimes(1);
    expect(onCancelEditingLastUserMessage).toHaveBeenCalledTimes(1);
    expect(onClearEditingSubmitError).toHaveBeenCalledTimes(1);
  });
});
