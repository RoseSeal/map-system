import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import type {
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

interface RiskState {
  latestRiskUpdate: RiskUpdatePayload | null;
  currentRiskObjectId: string | null;
  lastUpdateTime: number;

  ownShip: OwnShip | null;
  targets: RiskTarget[];
  governance: Governance | null;
  environment: EnvironmentContext | null;
  explanationsByTargetId: Record<string, ExplanationPayload>;

  isConnected: boolean;
  connectionError: string | null;
  lastError: SseErrorPayload | null;

  isLowTrust: boolean;
  selectedTargetId: string | null;

  setRiskUpdate: (payload: RiskUpdatePayload) => void;
  upsertExplanation: (payload: ExplanationPayload) => void;
  setConnectionStatus: (connected: boolean, error?: string | null) => void;
  setRiskError: (payload: SseErrorPayload) => void;
  clearRiskError: () => void;
  selectTarget: (targetId: string | null) => void;
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
  isConnected: false,
  connectionError: null,
  lastError: null as SseErrorPayload | null,
  isLowTrust: false,
  selectedTargetId: null as string | null,
};

export const useRiskStore = create<RiskState>()(
  subscribeWithSelector((set) => ({
    ...initialState,

    setRiskUpdate: (payload: RiskUpdatePayload) => {
      set((state) => {
        const targetIds = new Set(payload.targets.map((target) => target.id));
        const explanationsByTargetId = Object.fromEntries(
          Object.entries(state.explanationsByTargetId).filter(([targetId]) => targetIds.has(targetId)),
        );

        return {
          latestRiskUpdate: payload,
          currentRiskObjectId: payload.risk_object_id,
          lastUpdateTime: Date.now(),
          ownShip: payload.own_ship,
          targets: payload.targets,
          governance: payload.governance,
          environment: payload.environment_context,
          explanationsByTargetId,
          isConnected: true,
          connectionError: null,
          isLowTrust: payload.governance.trust_factor < PERFORMANCE.LOW_TRUST_THRESHOLD,
        };
      });
    },

    upsertExplanation: (payload: ExplanationPayload) => {
      set((state) => {
        if (!state.targets.some((target) => target.id === payload.target_id)) {
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

    setConnectionStatus: (connected: boolean, error: string | null = null) => {
      set({
        isConnected: connected,
        connectionError: connected ? null : error,
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
      set({ selectedTargetId: targetId });
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
export const selectIsConnected = (state: RiskState) => state.isConnected;
export const selectRiskConnectionError = (state: RiskState) => state.connectionError;
export const selectExplanationsByTargetId = (state: RiskState) => state.explanationsByTargetId;
export const selectSelectedTargetId = (state: RiskState) => state.selectedTargetId;
export const selectSelectedTarget = (state: RiskState) => {
  if (!state.selectedTargetId) {
    return null;
  }

  return state.targets.find((target) => target.id === state.selectedTargetId) || null;
};
export const selectSelectedTargetExplanation = (state: RiskState) => {
  if (!state.selectedTargetId) {
    return null;
  }

  return state.explanationsByTargetId[state.selectedTargetId] || null;
};
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

  riskSseService.onError((payload) => {
    useRiskStore.getState().setRiskError(payload);
  });

  riskSseService.onConnectionStatusChange((connected, error) => {
    useRiskStore.getState().setConnectionStatus(connected, error);
  });
}

initializeRiskStoreSubscriptions();
