import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  advisoryFixture,
  advisoryFixture2,
  environmentUpdateFixture,
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
  onEnvironmentUpdate: undefined as ((payload: unknown) => void) | undefined,
  onExplanation: undefined as ((payload: unknown) => void) | undefined,
  onAdvisory: undefined as ((payload: unknown) => void) | undefined,
  onError: undefined as ((payload: unknown) => void) | undefined,
  onConnectionStatusChange: undefined as ((state: DisplayConnectionState, error?: string | null) => void) | undefined,
}));

const riskSseServiceMock = vi.hoisted(() => ({
  onRiskUpdate: vi.fn((cb: (payload: unknown) => void) => {
    riskSubscribers.onRiskUpdate = cb;
    return vi.fn();
  }),
  onEnvironmentUpdate: vi.fn((cb: (payload: unknown) => void) => {
    riskSubscribers.onEnvironmentUpdate = cb;
    return vi.fn();
  }),
  onExplanation: vi.fn((cb: (payload: unknown) => void) => {
    riskSubscribers.onExplanation = cb;
    return vi.fn();
  }),
  onAdvisory: vi.fn((cb: (payload: unknown) => void) => {
    riskSubscribers.onAdvisory = cb;
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
    expect(state.environment).toBeNull();
    expect(state.riskConnectionState).toBe('connected');
    expect(state.connectionError).toBeNull();
    expect(state.isLowTrust).toBe(true);
    expect(state.lastUpdateTime).toBeGreaterThan(0);
  });

  it('writes environment update without changing risk fields', () => {
    const store = useRiskStore.getState();
    store.setRiskUpdate(riskUpdateFixture);
    store.setEnvironmentUpdate(environmentUpdateFixture);

    const state = useRiskStore.getState();
    expect(state.currentRiskObjectId).toBe('risk-object-1');
    expect(state.targets).toHaveLength(2);
    expect(state.environment?.safety_contour_val).toBe(15);
    expect(state.environment?.active_alerts).toEqual(['LOW_VISIBILITY']);
    expect(state.lastEnvironmentUpdateTime).toBeGreaterThan(0);
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

  it('keeps dropped target notice reference stable until new notices arrive', () => {
    const store = useRiskStore.getState();

    store.setRiskUpdate(riskUpdateFixture);
    store.selectTarget('TGT-ALARM');
    store.setRiskUpdate(riskUpdateWithoutAlarmFixture);
    const firstNoticeRef = useRiskStore.getState().droppedTargetNotices;

    store.setRiskUpdate({
      ...riskUpdateWithoutAlarmFixture,
      event_id: 'risk-event-3',
      risk_object_id: 'risk-object-3',
    });

    expect(useRiskStore.getState().droppedTargetNotices).toBe(firstNoticeRef);
  });

  it('uses backend trust factor directly for the low-trust flag', () => {
    useRiskStore.getState().setRiskUpdate({
      ...riskUpdateFixture,
      targets: [],
      governance: {
        mode: 'adaptive',
        trust_factor: 0,
      },
    });

    const state = useRiskStore.getState();
    expect(state.governance?.trust_factor).toBe(0);
    expect(state.isLowTrust).toBe(true);
  });

  it('clears explanation when a tracked target downgrades to SAFE', () => {
    const store = useRiskStore.getState();

    store.setRiskUpdate(riskUpdateFixture);
    store.upsertExplanation(explanationForAlarmFixture);
    store.upsertExplanation(explanationForCautionFixture);

    store.setRiskUpdate({
      ...riskUpdateFixture,
      event_id: 'risk-event-safe',
      risk_object_id: 'risk-object-safe',
      targets: riskUpdateFixture.targets.map((target) => (
        target.id === 'TGT-ALARM'
          ? {
            ...target,
            risk_assessment: {
              ...target.risk_assessment,
              risk_level: 'SAFE',
            },
          }
          : target
      )),
    });

    const state = useRiskStore.getState();
    expect(Object.keys(state.explanationsByTargetId)).toEqual(['TGT-CAUTION']);
    expect(state.targets.find((target) => target.id === 'TGT-ALARM')?.risk_assessment.risk_level).toBe('SAFE');
  });

  it('only upserts explanations for existing targets', () => {
    const store = useRiskStore.getState();

    store.setRiskUpdate(riskUpdateFixture);
    store.upsertExplanation(explanationForMissingTargetFixture);
    expect(Object.keys(useRiskStore.getState().explanationsByTargetId)).toHaveLength(0);

    store.upsertExplanation(explanationForAlarmFixture);
    expect(useRiskStore.getState().explanationsByTargetId['TGT-ALARM']?.event_id).toBe('explanation-1');
  });

  it('ignores out-of-order explanations for the same target', () => {
    const store = useRiskStore.getState();

    store.setRiskUpdate(riskUpdateFixture);
    store.upsertExplanation({
      ...explanationForAlarmFixture,
      event_id: 'explanation-newer',
      text: 'Newest alarm explanation should stay visible.',
      timestamp: '2026-04-15T12:00:20.000Z',
    });
    store.upsertExplanation({
      ...explanationForAlarmFixture,
      event_id: 'explanation-older',
      text: 'Older alarm explanation must not overwrite the latest text.',
      timestamp: '2026-04-15T12:00:10.000Z',
    });

    const explanation = useRiskStore.getState().explanationsByTargetId['TGT-ALARM'];
    expect(explanation?.event_id).toBe('explanation-newer');
    expect(explanation?.text).toBe('Newest alarm explanation should stay visible.');
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
    expect(typeof riskSubscribers.onEnvironmentUpdate).toBe('function');
    expect(typeof riskSubscribers.onExplanation).toBe('function');
    expect(typeof riskSubscribers.onAdvisory).toBe('function');
    expect(typeof riskSubscribers.onError).toBe('function');
    expect(typeof riskSubscribers.onConnectionStatusChange).toBe('function');

    riskSubscribers.onRiskUpdate?.(riskUpdateFixture);
    riskSubscribers.onEnvironmentUpdate?.(environmentUpdateFixture);
    riskSubscribers.onExplanation?.(explanationForAlarmFixture);
    riskSubscribers.onAdvisory?.(advisoryFixture);
    riskSubscribers.onError?.(sseErrorFixture);
    riskSubscribers.onConnectionStatusChange?.('disconnected', 'stream-down');

    const state = useRiskStore.getState();
    expect(state.targets).toHaveLength(2);
    expect(state.environment?.safety_contour_val).toBe(15);
    expect(state.explanationsByTargetId['TGT-ALARM']?.provider).toBe('gemini');
    expect(state.activeAdvisory?.advisory_id).toBe('advisory-uuid-1');
    expect(state.lastError?.error_code).toBe('RISK_STREAM_INTERRUPTED');
    expect(state.riskConnectionState).toBe('disconnected');
    expect(state.connectionError).toBe('stream-down');
  });

  it('upsertAdvisory sets active advisory', () => {
    useRiskStore.getState().upsertAdvisory(advisoryFixture);

    const state = useRiskStore.getState();
    expect(state.activeAdvisory?.advisory_id).toBe('advisory-uuid-1');
    expect(state.archivedAdvisories).toHaveLength(0);
  });

  it('second advisory supersedes first and archives it', () => {
    const store = useRiskStore.getState();
    store.upsertAdvisory(advisoryFixture);
    store.upsertAdvisory(advisoryFixture2);

    const state = useRiskStore.getState();
    expect(state.activeAdvisory?.advisory_id).toBe('advisory-uuid-2');
    expect(state.archivedAdvisories).toHaveLength(1);
    expect(state.archivedAdvisories[0].advisory_id).toBe('advisory-uuid-1');
    expect(state.archivedAdvisories[0].status).toBe('SUPERSEDED');
  });

  it('expireActiveAdvisory clears active advisory when id matches', () => {
    const store = useRiskStore.getState();
    store.upsertAdvisory(advisoryFixture);
    store.expireActiveAdvisory('advisory-uuid-1');

    expect(useRiskStore.getState().activeAdvisory).toBeNull();
  });

  it('expireActiveAdvisory is no-op when id does not match', () => {
    const store = useRiskStore.getState();
    store.upsertAdvisory(advisoryFixture);
    store.expireActiveAdvisory('advisory-uuid-WRONG');

    expect(useRiskStore.getState().activeAdvisory?.advisory_id).toBe('advisory-uuid-1');
  });

  it('duplicate advisory with same id is ignored', () => {
    const store = useRiskStore.getState();
    store.upsertAdvisory(advisoryFixture);
    store.upsertAdvisory(advisoryFixture); // same id

    const state = useRiskStore.getState();
    expect(state.archivedAdvisories).toHaveLength(0);
    expect(state.activeAdvisory?.advisory_id).toBe('advisory-uuid-1');
  });

  it('migrates explanation to resolvedExplanations when target turns SAFE', () => {
    const store = useRiskStore.getState();

    store.setRiskUpdate(riskUpdateFixture);
    store.upsertExplanation(explanationForAlarmFixture);

    store.setRiskUpdate({
      ...riskUpdateFixture,
      event_id: 'risk-event-safe',
      targets: riskUpdateFixture.targets.map((t) =>
        t.id === 'TGT-ALARM'
          ? { ...t, risk_assessment: { ...t.risk_assessment, risk_level: 'SAFE' } }
          : t,
      ),
    });

    const state = useRiskStore.getState();
    expect(Object.keys(state.explanationsByTargetId)).not.toContain('TGT-ALARM');
    expect(state.resolvedExplanations).toHaveLength(1);
    const resolved = state.resolvedExplanations[0];
    expect(resolved.event_id).toBe('explanation-1');
    expect(resolved.status).toBe('RESOLVED');
    expect(resolved.resolved_reason).toBe('TARGET_SAFE');
    expect(resolved.risk_level).toBe('ALARM');
    expect(resolved.current_risk_level).toBe('SAFE');
    expect(resolved.resolved_at).toBeTruthy();
  });

  it('migrates explanation to resolvedExplanations when target leaves snapshot', () => {
    const store = useRiskStore.getState();

    store.setRiskUpdate(riskUpdateFixture);
    store.upsertExplanation(explanationForAlarmFixture);
    store.setRiskUpdate(riskUpdateWithoutAlarmFixture);

    const state = useRiskStore.getState();
    expect(state.resolvedExplanations).toHaveLength(1);
    const resolved = state.resolvedExplanations[0];
    expect(resolved.event_id).toBe('explanation-1');
    expect(resolved.resolved_reason).toBe('TARGET_MISSING');
    expect(resolved.current_risk_level).toBeNull();
  });

  it('applyExpiredExplanationsCleared removes only the listed event_ids', () => {
    const store = useRiskStore.getState();

    store.setRiskUpdate(riskUpdateFixture);
    store.upsertExplanation(explanationForAlarmFixture);
    store.upsertExplanation(explanationForCautionFixture);

    store.setRiskUpdate({
      ...riskUpdateFixture,
      event_id: 'risk-event-all-safe',
      targets: riskUpdateFixture.targets.map((t) => ({
        ...t,
        risk_assessment: { ...t.risk_assessment, risk_level: 'SAFE' },
      })),
    });

    expect(useRiskStore.getState().resolvedExplanations).toHaveLength(2);

    store.applyExpiredExplanationsCleared(['explanation-1']);

    const after = useRiskStore.getState().resolvedExplanations;
    expect(after).toHaveLength(1);
    expect(after[0].event_id).toBe('explanation-2');
  });

  it('resolvedExplanations count limit evicts oldest resolved entries beyond 20', () => {
    const store = useRiskStore.getState();

    vi.useFakeTimers();
    try {
      const baseMs = new Date('2026-04-27T09:00:00.000Z').getTime();

      for (let i = 0; i < 21; i++) {
        vi.setSystemTime(baseMs + i * 60_000);

        const targetId = `TGT-MULTI-${i}`;
        store.setRiskUpdate({
          ...riskUpdateFixture,
          event_id: `risk-setup-${i}`,
          targets: [{
            id: targetId,
            tracking_status: 'tracking',
            position: { lon: 0, lat: 0 },
            vector: { speed_kn: 0, course_deg: 0 },
            risk_assessment: { risk_level: 'CAUTION', cpa_metrics: { dcpa_nm: 1, tcpa_sec: 1000 } },
          }],
        });
        store.upsertExplanation({
          event_id: `exp-${i}`,
          risk_object_id: 'risk-object-1',
          target_id: targetId,
          risk_level: 'CAUTION',
          provider: 'gemini',
          text: `Explanation ${i}`,
          timestamp: new Date(baseMs + i * 60_000).toISOString(),
        });
        store.setRiskUpdate({
          ...riskUpdateFixture,
          event_id: `risk-resolve-${i}`,
          targets: [],
        });
      }

      const state = useRiskStore.getState();
      expect(state.resolvedExplanations).toHaveLength(20);
      expect(state.resolvedExplanations.every((e) => e.event_id !== 'exp-0')).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it('applyExpiredExplanationsCleared evicts TTL-expired entries even when removed list is empty', () => {
    const store = useRiskStore.getState();

    vi.useFakeTimers();
    try {
      const baseMs = new Date('2026-04-27T08:00:00.000Z').getTime();
      vi.setSystemTime(baseMs);

      store.setRiskUpdate(riskUpdateFixture);
      store.upsertExplanation(explanationForAlarmFixture);
      store.setRiskUpdate(riskUpdateWithoutAlarmFixture);

      expect(useRiskStore.getState().resolvedExplanations).toHaveLength(1);

      vi.setSystemTime(baseMs + 31 * 60_000);

      // Backend already pruned the entry; returns empty list
      store.applyExpiredExplanationsCleared([]);

      expect(useRiskStore.getState().resolvedExplanations).toHaveLength(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('resolvedExplanations TTL eviction triggers on write, not on selector read', () => {
    const store = useRiskStore.getState();

    vi.useFakeTimers();
    try {
      const baseMs = new Date('2026-04-27T10:00:00.000Z').getTime();
      vi.setSystemTime(baseMs);

      store.setRiskUpdate(riskUpdateFixture);
      store.upsertExplanation(explanationForAlarmFixture);
      store.setRiskUpdate(riskUpdateWithoutAlarmFixture);

      vi.setSystemTime(baseMs + 31 * 60_000);

      // Reading state does NOT trigger eviction
      expect(useRiskStore.getState().resolvedExplanations).toHaveLength(1);

      // Next write evicts the expired entry
      store.setRiskUpdate({
        ...riskUpdateFixture,
        event_id: 'risk-event-trigger',
        targets: [riskUpdateFixture.targets[1]],
      });

      expect(useRiskStore.getState().resolvedExplanations).toHaveLength(0);
    } finally {
      vi.useRealTimers();
    }
  });
});
