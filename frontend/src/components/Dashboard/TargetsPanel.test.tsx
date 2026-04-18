import { render, screen } from '@testing-library/react';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { riskUpdateFixture } from '../../test/fixtures';
import type { DisplayConnectionState } from '../../types/connection';

const riskSubscribers = vi.hoisted(() => ({
  onRiskUpdate: undefined as ((payload: unknown) => void) | undefined,
  onExplanation: undefined as ((payload: unknown) => void) | undefined,
  onError: undefined as ((payload: unknown) => void) | undefined,
  onConnectionStatusChange: undefined as ((state: DisplayConnectionState, error?: string | null) => void) | undefined,
}));

const riskSseServiceMock = vi.hoisted(() => ({
  onRiskUpdate: vi.fn((cb: (payload: unknown) => void) => {
    riskSubscribers.onRiskUpdate = cb;
    return vi.fn();
  }),
  onExplanation: vi.fn((cb: (payload: unknown) => void) => {
    riskSubscribers.onExplanation = cb;
    return vi.fn();
  }),
  onError: vi.fn((cb: (payload: unknown) => void) => {
    riskSubscribers.onError = cb;
    return vi.fn();
  }),
  onConnectionStatusChange: vi.fn((cb: (state: DisplayConnectionState, error?: string | null) => void) => {
    riskSubscribers.onConnectionStatusChange = cb;
    return vi.fn();
  }),
}));

const chatWsServiceMock = vi.hoisted(() => ({
  send: vi.fn(),
  sendClearHistory: vi.fn(),
  onChatReply: vi.fn(() => vi.fn()),
  onSpeechTranscript: vi.fn(() => vi.fn()),
  onError: vi.fn(() => vi.fn()),
  onClearHistoryAck: vi.fn(() => vi.fn()),
  onConnectionStateChange: vi.fn(() => vi.fn()),
}));

vi.mock('../../services/riskSseService', () => ({
  riskSseService: riskSseServiceMock,
}));

vi.mock('../../services/chatWsService', () => ({
  chatWsService: chatWsServiceMock,
}));

import { useAiCenterStore, useRiskStore } from '../../store';
import { TargetsPanel } from './TargetsPanel';

describe('TargetsPanel smoke', () => {
  beforeEach(() => {
    useRiskStore.getState().reset();
    useAiCenterStore.getState().reset();
  });

  it('renders targets from risk update callback and keeps risk-level sorting', () => {
    render(<TargetsPanel />);

    expect(screen.queryByText('周边目标')).toBeNull();
    expect(typeof riskSubscribers.onRiskUpdate).toBe('function');

    act(() => {
      riskSubscribers.onRiskUpdate?.(riskUpdateFixture);
    });

    expect(screen.getByText('周边目标')).toBeInTheDocument();
    expect(screen.getByText(/2\s+已追踪/)).toBeInTheDocument();
    expect(screen.getByText('警报')).toBeInTheDocument();
    expect(screen.getByText('注意')).toBeInTheDocument();

    const targetRows = screen.getAllByRole('button').map((element) => element.textContent ?? '');
    expect(targetRows[0]).toContain('TGT-ALARM');
    expect(targetRows[1]).toContain('TGT-CAUTION');
  });
});
