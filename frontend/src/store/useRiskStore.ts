/**
 * Risk Store
 * Central state management for RiskObject data
 * Uses Zustand for high-frequency socket updates
 */
/**
 * useRiskStore 鏄〉闈㈢骇鐘舵€佷腑蹇?
 * 璐熻矗鎵挎帴 WebSocket 杈撳叆鐨勯闄╀笟鍔℃暟鎹紝鍚屾椂缁存姢杩炴帴鐘舵€佸拰鍓嶇浜や簰鎬?
 * 骞跺皢閮ㄥ垎鏍稿績涓氬姟瀛楁鎵佸钩鍖?娲剧敓鍖栵紝鏂逛究缁勪欢鎸夐渶璁㈤槄鍜屾覆鏌撱€俙
 */

import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import type { RiskObject, OwnShip, Target, EnvironmentContext, Governance, RiskExplanation } from '../types/schema';
import { PERFORMANCE } from '../config/constants';

interface RiskState {
  riskObject: RiskObject | null;
  lastUpdateTime: number;

  ownShip: OwnShip | null;
  targets: Target[];
  allTargets: Target[];
  governance: Governance | null;
  environment: EnvironmentContext | null;
  latestLlmExplanations: Record<string, RiskExplanation>;

  isConnected: boolean;
  connectionError: string | null;

  isLowTrust: boolean;
  selectedTargetId: string | null;

  setRiskObject: (data: RiskObject) => void;
  setConnectionStatus: (connected: boolean, error?: string | null) => void;
  selectTarget: (targetId: string | null) => void;
  reset: () => void;
}

const initialState = {
  riskObject: null,
  lastUpdateTime: 0,
  ownShip: null,
  targets: [],
  allTargets: [],
  governance: null,
  environment: null,
  latestLlmExplanations: {},
  isConnected: false,
  connectionError: null,
  isLowTrust: false,
  selectedTargetId: null,
};

export const useRiskStore = create<RiskState>()(
  subscribeWithSelector((set) => ({
    ...initialState,

    setRiskObject: (data: RiskObject) => {
      set((state) => {
        const nextLatestLlmExplanations = { ...state.latestLlmExplanations };
        const mergedTargets = data.all_targets || data.targets;

        mergedTargets.forEach((target) => {
          const explanation = target.risk_assessment.explanation;
          if (isLlmExplanation(explanation)) {
            nextLatestLlmExplanations[target.id] = explanation;
          }
        });

        return {
          riskObject: data,
          lastUpdateTime: Date.now(),
          ownShip: data.own_ship,
          targets: data.targets,
          allTargets: mergedTargets,
          governance: data.governance,
          environment: data.environment_context,
          latestLlmExplanations: nextLatestLlmExplanations,
          isLowTrust: data.governance.trust_factor < PERFORMANCE.LOW_TRUST_THRESHOLD,
        };
      });
    },

    setConnectionStatus: (connected: boolean, error: string | null = null) => {
      set({
        isConnected: connected,
        connectionError: error,
      });
    },

    selectTarget: (targetId: string | null) => {
      set({ selectedTargetId: targetId });
    },

    reset: () => {
      set(initialState);
    },
  }))
);

export const selectOwnShip = (state: RiskState) => state.ownShip;
export const selectTargets = (state: RiskState) => state.targets;
export const selectAllTargets = (state: RiskState) => state.allTargets;
export const selectGovernance = (state: RiskState) => state.governance;
export const selectEnvironment = (state: RiskState) => state.environment;
export const selectIsLowTrust = (state: RiskState) => state.isLowTrust;
export const selectIsConnected = (state: RiskState) => state.isConnected;
export const selectSelectedTarget = (state: RiskState) => {
  if (!state.selectedTargetId) return null;
  return state.targets.find((target) => target.id === state.selectedTargetId)
    || state.allTargets.find((target) => target.id === state.selectedTargetId)
    || null;
};
export const selectLlmExplainedHighRiskTargets = (state: RiskState) =>
  state.allTargets.filter((target) => {
    const llmExplanation = state.latestLlmExplanations[target.id];
    return Boolean(llmExplanation?.text?.trim())
      && (target.risk_assessment.risk_level === 'WARNING' || target.risk_assessment.risk_level === 'ALARM');
  });
export const selectSelectedTargetLlmExplanation = (state: RiskState) => {
  if (!state.selectedTargetId) return null;
  return state.latestLlmExplanations[state.selectedTargetId] ?? null;
};

function isLlmExplanation(explanation?: RiskExplanation | null): explanation is RiskExplanation {
  return Boolean(
    explanation?.text?.trim()
    && (explanation.source || '').toLowerCase() === 'llm',
  );
}
