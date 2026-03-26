/**
 * Risk Store
 * Central state management for RiskObject data
 * Uses Zustand for high-frequency socket updates
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
  readLlmExplanations: Record<string, boolean>;

  isConnected: boolean;
  connectionError: string | null;

  isLowTrust: boolean;
  selectedTargetId: string | null;

  setRiskObject: (data: RiskObject) => void;
  setConnectionStatus: (connected: boolean, error?: string | null) => void;
  selectTarget: (targetId: string | null) => void;
  markLlmRead: (targetId: string) => void;
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
  readLlmExplanations: {},
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

    markLlmRead: (targetId: string) => {
      set((state) => ({
        readLlmExplanations: {
          ...state.readLlmExplanations,
          [targetId]: true,
        },
      }));
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
  const source = (explanation?.source || '').toLowerCase();
  return Boolean(
    explanation?.text?.trim()
    && (
      source === 'llm'
      || source.includes('llm')
      || source.includes('ai')
      || source.includes('model')
      || source.includes('gpt')
    ),
  );
}
