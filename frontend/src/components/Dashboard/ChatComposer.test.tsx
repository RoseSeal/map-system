import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ChatComposer } from './ChatComposer';

describe('ChatComposer', () => {
  it('renders a cancel recording button and calls the callback in recording state', () => {
    const onCancelVoiceRecording = vi.fn();

    render(
      <ChatComposer
        value=""
        voiceSupported
        voiceState="recording"
        onChange={vi.fn()}
        onSend={vi.fn()}
        onStartVoiceRecording={vi.fn()}
        onStopVoiceRecording={vi.fn()}
        onCancelVoiceRecording={onCancelVoiceRecording}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '取消' }));

    expect(onCancelVoiceRecording).toHaveBeenCalledTimes(1);
  });
});
