import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import type {
  AdvisoryPayload,
  EnvironmentContext,
  ExplanationPayload,
  Governance,
  OwnShip,
  RiskTarget,
  RiskUpdatePayload,
  SseErrorPayload,
} from '../types/schema';
import { PERFORMANCE } from '../config/constants';
import { riskSseService } from '../services/riskSseService';
import type { DisplayConnectionState } from '../types/connection';

interface RiskState {
  latestRiskUpdate: RiskUpdatePayload | null;
  currentRiskObjectId: string | null;
  lastUpdateTime: number;

  ownShip: OwnShip | null;
  targets: RiskTarget[];
  governance: Governance | null;
  environment: EnvironmentContext | null;
  explanationsByTargetId: Record<string, ExplanationPayload>;

  activeAdvisory: AdvisoryPayload | null;
  archivedAdvisories: AdvisoryPayload[];

  riskConnectionState: DisplayConnectionState;
  connectionError: string | null;
  lastError: SseErrorPayload | null;

  isLowTrust: boolean;
  selectedTargetIds: string[];
  droppedTargetNotices: string[];

  setRiskUpdate: (payload: RiskUpdatePayload) => void;
  upsertExplanation: (payload: ExplanationPayload) => void;
  upsertAdvisory: (payload: AdvisoryPayload) => void;
  expireActiveAdvisory: (advisoryId: string) => void;
  setRiskConnectionState: (state: DisplayConnectionState, error?: string | null) => void;
  setRiskError: (payload: SseErrorPayload) => void;
  clearRiskError: () => void;
  selectTarget: (targetId: string | null) => void;
  deselectTarget: (targetId: string) => void;
  clearDroppedTargetNotices: () => void;
  reset: () => void;
}

const initialState = {
  latestRiskUpdate: null,
  currentRiskObjectId: null,
  lastUpdateTime: 0,
  ownShip: null,
  targets: [] as RiskTarget[],
  governance: null,
  environment: null,
  explanationsByTargetId: {} as Record<string, ExplanationPayload>,
  activeAdvisory: null as AdvisoryPayload | null,
  archivedAdvisories: [] as AdvisoryPayload[],
  riskConnectionState: 'disconnected' as DisplayConnectionState,
  connectionError: null,
  lastError: null as SseErrorPayload | null,
  isLowTrust: false,
  selectedTargetIds: [] as string[],
  droppedTargetNotices: [] as string[],
};

function parseTimestampMs(timestamp: string): number | null {
  const value = Date.parse(timestamp);
  return Number.isNaN(value) ? null : value;
}

function shouldKeepExistingExplanation(
  existing: ExplanationPayload | undefined,
  incoming: ExplanationPayload,
): boolean {
  if (!existing) {
    return false;
  }

  const existingTimestampMs = parseTimestampMs(existing.timestamp);
  const incomingTimestampMs = parseTimestampMs(incoming.timestamp);
  if (existingTimestampMs !== null && incomingTimestampMs !== null && incomingTimestampMs < existingTimestampMs) {
    return true;
  }

  return existing.event_id === incoming.event_id
    && existing.risk_object_id === incoming.risk_object_id
    && existing.risk_level === incoming.risk_level
    && existing.provider === incoming.provider
    && existing.text === incoming.text
    && existing.timestamp === incoming.timestamp;
}

function mergeDroppedTargetNotices(existing: string[], incoming: string[]): string[] {
  if (incoming.length === 0) {
    return existing;
  }

  const seen = new Set(existing);
  let changed = false;
  const next = [...existing];
  incoming.forEach((targetId) => {
    if (!seen.has(targetId)) {
      seen.add(targetId);
      next.push(targetId);
      changed = true;
    }
  });

  return changed ? next : existing;
}

export const useRiskStore = create<RiskState>()(
  subscribeWithSelector((set) => ({
    ...initialState,

    setRiskUpdate: (payload: RiskUpdatePayload) => {
      set((state) => {
        const activeRiskTargetIds = new Set(
          payload.targets
            .filter((target) => target.risk_assessment.risk_level !== 'SAFE')
            .map((target) => target.id),
        );
        const trackedTargetIds = new Set(payload.targets.map((target) => target.id));
        const explanationsByTargetId = Object.fromEntries(
          Object.entries(state.explanationsByTargetId).filter(([targetId]) => activeRiskTargetIds.has(targetId)),
        );
        const selectedTargetIds = state.selectedTargetIds.filter((id) => trackedTargetIds.has(id));
        const droppedTargetNotices = state.selectedTargetIds.filter((id) => !trackedTargetIds.has(id));
        const nextDroppedTargetNotices = mergeDroppedTargetNotices(state.droppedTargetNotices, droppedTargetNotices);

        return {
          latestRiskUpdate: payload,
          currentRiskObjectId: payload.risk_object_id,
          lastUpdateTime: Date.now(),
          ownShip: payload.own_ship,
          targets: payload.targets,
          governance: payload.governance,
          environment: payload.environment_context,
          explanationsByTargetId,
          selectedTargetIds,
          droppedTargetNotices: nextDroppedTargetNotices,
          riskConnectionState: 'connected',
          connectionError: null,
          isLowTrust: payload.governance.trust_factor < PERFORMANCE.LOW_TRUST_THRESHOLD,
        };
      });
    },

    upsertAdvisory: (payload: AdvisoryPayload) => {
      set((state) => {
        const prev = state.activeAdvisory;
        if (prev && prev.advisory_id === payload.advisory_id) {
          return state;
        }
        const archived = prev
          ? [...state.archivedAdvisories, { ...prev, status: 'SUPERSEDED' as const }]
          : state.archivedAdvisories;
        return {
          activeAdvisory: payload,
          archivedAdvisories: archived,
        };
      });
    },

    expireActiveAdvisory: (advisoryId: string) => {
      set((state) => {
        if (!state.activeAdvisory || state.activeAdvisory.advisory_id !== advisoryId) {
          return state;
        }
        return { activeAdvisory: null };
      });
    },

    upsertExplanation: (payload: ExplanationPayload) => {
      set((state) => {
        if (!state.targets.some((target) => target.id === payload.target_id)) {
          return state;
        }

        const existing = state.explanationsByTargetId[payload.target_id];
        if (shouldKeepExistingExplanation(existing, payload)) {
          return state;
        }

        return {
          explanationsByTargetId: {
            ...state.explanationsByTargetId,
            [payload.target_id]: payload,
          },
        };
      });
    },

    setRiskConnectionState: (state: DisplayConnectionState, error: string | null = null) => {
      set({
        riskConnectionState: state,
        connectionError: state === 'connected' ? null : error,
      });
    },

    setRiskError: (payload: SseErrorPayload) => {
      set({
        lastError: payload,
      });
    },

    clearRiskError: () => {
      set({
        connectionError: null,
        lastError: null,
      });
    },

    selectTarget: (targetId: string | null) => {
      if (targetId === null) {
        set({ selectedTargetIds: [] });
        return;
      }
      set((state) => {
        const ids = state.selectedTargetIds;
        if (ids.includes(targetId)) {
          return { selectedTargetIds: ids.filter((id) => id !== targetId) };
        }
        return { selectedTargetIds: [...ids, targetId] };
      });
    },

    deselectTarget: (targetId: string) => {
      set((state) => ({
        selectedTargetIds: state.selectedTargetIds.filter((id) => id !== targetId),
      }));
    },

    clearDroppedTargetNotices: () => {
      set({ droppedTargetNotices: [] });
    },

    reset: () => {
      set(initialState);
    },
  })),
);

export const selectOwnShip = (state: RiskState) => state.ownShip;
export const selectTargets = (state: RiskState) => state.targets;
export const selectGovernance = (state: RiskState) => state.governance;
export const selectEnvironment = (state: RiskState) => state.environment;
export const selectIsLowTrust = (state: RiskState) => state.isLowTrust;
export const selectRiskConnectionState = (state: RiskState) => state.riskConnectionState;
export const selectRiskConnectionError = (state: RiskState) => state.connectionError;
export const selectExplanationsByTargetId = (state: RiskState) => state.explanationsByTargetId;
export const selectSelectedTargetIds = (state: RiskState) => state.selectedTargetIds;
export const selectDroppedTargetNotices = (state: RiskState) => state.droppedTargetNotices;
export const selectActiveAdvisory = (state: RiskState) => state.activeAdvisory;
export const selectArchivedAdvisories = (state: RiskState) => state.archivedAdvisories;
let hasInitializedRiskStoreSubscriptions = false;

function initializeRiskStoreSubscriptions(): void {
  if (hasInitializedRiskStoreSubscriptions) {
    return;
  }

  hasInitializedRiskStoreSubscriptions = true;

  riskSseService.onRiskUpdate((payload) => {
    useRiskStore.getState().setRiskUpdate(payload);
  });

  riskSseService.onExplanation((payload) => {
    useRiskStore.getState().upsertExplanation(payload);
  });

  riskSseService.onAdvisory((payload) => {
    useRiskStore.getState().upsertAdvisory(payload);
  });

  riskSseService.onError((payload) => {
    useRiskStore.getState().setRiskError(payload);
  });

  riskSseService.onConnectionStatusChange((state, error) => {
    useRiskStore.getState().setRiskConnectionState(state, error);
  });
}

initializeRiskStoreSubscriptions();
