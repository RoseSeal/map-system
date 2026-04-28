import { create } from 'zustand';
import type { OwnShipFollowMode } from '../types/schema';

interface MapSettingsState {
  pendingSafetyContourValue: number | null;
  defaultSafetyContourValue: number | null;
  safetyContourError: string | null;
  setPendingSafetyContourValue: (val: number | null) => void;
  setDefaultSafetyContourValue: (val: number | null) => void;
  setSafetyContourError: (message: string | null) => void;
  followMode: OwnShipFollowMode;
  setFollowMode: (mode: OwnShipFollowMode) => void;
}

export const useMapSettingsStore = create<MapSettingsState>()((set) => ({
  pendingSafetyContourValue: null,
  defaultSafetyContourValue: null,
  safetyContourError: null,
  setPendingSafetyContourValue: (val) => set({ pendingSafetyContourValue: val }),
  setDefaultSafetyContourValue: (val) => set({ defaultSafetyContourValue: val }),
  setSafetyContourError: (message) => set({ safetyContourError: message }),
  followMode: 'SOFT',
  setFollowMode: (mode) => set({ followMode: mode }),
}));
