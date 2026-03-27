/**
 * Risk Store
 * High-frequency physical snapshot state only.
 */
import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import type { RiskObject, OwnShip, Target, EnvironmentContext, Governance } from '../types/schema';
import { PERFORMANCE } from '../config/constants';

interface RiskState {
  riskObject: RiskObject | null;
  lastUpdateTime: number;

  ownShip: OwnShip | null;
  targets: Target[];
  allTargets: Target[];
  governance: Governance | null;
  environment: EnvironmentContext | null;

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
  isConnected: false,
  connectionError: null,
  isLowTrust: false,
  selectedTargetId: null,
};

export const useRiskStore = create<RiskState>()(
  subscribeWithSelector((set) => ({
    ...initialState,

    setRiskObject: (data: RiskObject) => {
      set({
        riskObject: data,
        lastUpdateTime: Date.now(),
        ownShip: data.own_ship,
        targets: data.targets,
        allTargets: data.all_targets || data.targets,
        governance: data.governance,
        environment: data.environment_context,
        isLowTrust: data.governance.trust_factor < PERFORMANCE.LOW_TRUST_THRESHOLD,
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
