import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  explanationForAlarmFixture,
  explanationForCautionFixture,
  explanationForMissingTargetFixture,
  riskUpdateFixture,
  riskUpdateWithoutAlarmFixture,
  sseErrorFixture,
} from '../test/fixtures';
import type { DisplayConnectionState } from '../types/connection';

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

vi.mock('../services/riskSseService', () => ({
  riskSseService: riskSseServiceMock,
}));

import { useRiskStore } from './useRiskStore';

describe('useRiskStore', () => {
  beforeEach(() => {
    useRiskStore.getState().reset();
  });

  it('writes risk update fields and low-trust flag', () => {
    useRiskStore.getState().setRiskUpdate(riskUpdateFixture);

    const state = useRiskStore.getState();
    expect(state.latestRiskUpdate?.event_id).toBe('risk-event-1');
    expect(state.currentRiskObjectId).toBe('risk-object-1');
    expect(state.ownShip?.id).toBe('OWN-001');
    expect(state.targets).toHaveLength(2);
    expect(state.governance?.trust_factor).toBe(0.35);
    expect(state.environment?.safety_contour_val).toBe(15);
    expect(state.riskConnectionState).toBe('connected');
    expect(state.connectionError).toBeNull();
    expect(state.isLowTrust).toBe(true);
    expect(state.lastUpdateTime).toBeGreaterThan(0);
  });

  it('clears dropped selections and explanations when target disappears', () => {
    const store = useRiskStore.getState();

    store.setRiskUpdate(riskUpdateFixture);
    store.selectTarget('TGT-ALARM');
    store.selectTarget('TGT-CAUTION');
    store.upsertExplanation(explanationForAlarmFixture);
    store.upsertExplanation(explanationForCautionFixture);

    store.setRiskUpdate(riskUpdateWithoutAlarmFixture);

    const state = useRiskStore.getState();
    expect(state.selectedTargetIds).toEqual(['TGT-CAUTION']);
    expect(Object.keys(state.explanationsByTargetId)).toEqual(['TGT-CAUTION']);
    expect(state.droppedTargetNotices).toEqual(['TGT-ALARM']);
  });

  it('only upserts explanations for existing targets', () => {
    const store = useRiskStore.getState();

    store.setRiskUpdate(riskUpdateFixture);
    store.upsertExplanation(explanationForMissingTargetFixture);
    expect(Object.keys(useRiskStore.getState().explanationsByTargetId)).toHaveLength(0);

    store.upsertExplanation(explanationForAlarmFixture);
    expect(useRiskStore.getState().explanationsByTargetId['TGT-ALARM']?.event_id).toBe('explanation-1');
  });

  it('supports select, deselect, toggle and clear-all selection', () => {
    const store = useRiskStore.getState();

    store.selectTarget('TGT-ALARM');
    expect(useRiskStore.getState().selectedTargetIds).toEqual(['TGT-ALARM']);

    store.selectTarget('TGT-ALARM');
    expect(useRiskStore.getState().selectedTargetIds).toEqual([]);

    store.selectTarget('TGT-ALARM');
    store.selectTarget('TGT-CAUTION');
    store.deselectTarget('TGT-ALARM');
    expect(useRiskStore.getState().selectedTargetIds).toEqual(['TGT-CAUTION']);

    store.selectTarget(null);
    expect(useRiskStore.getState().selectedTargetIds).toEqual([]);
  });

  it('responds to risk service subscription callbacks', () => {
    expect(typeof riskSubscribers.onRiskUpdate).toBe('function');
    expect(typeof riskSubscribers.onExplanation).toBe('function');
    expect(typeof riskSubscribers.onError).toBe('function');
    expect(typeof riskSubscribers.onConnectionStatusChange).toBe('function');

    riskSubscribers.onRiskUpdate?.(riskUpdateFixture);
    riskSubscribers.onExplanation?.(explanationForAlarmFixture);
    riskSubscribers.onError?.(sseErrorFixture);
    riskSubscribers.onConnectionStatusChange?.('disconnected', 'stream-down');

    const state = useRiskStore.getState();
    expect(state.targets).toHaveLength(2);
    expect(state.explanationsByTargetId['TGT-ALARM']?.provider).toBe('gemini');
    expect(state.lastError?.error_code).toBe('RISK_STREAM_INTERRUPTED');
    expect(state.riskConnectionState).toBe('disconnected');
    expect(state.connectionError).toBe('stream-down');
  });
});
