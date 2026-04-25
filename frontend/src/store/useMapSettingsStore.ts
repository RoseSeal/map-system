import { create } from 'zustand';
import type { OwnShipFollowMode } from '../types/schema';

interface MapSettingsState {
  safetyContourOverride: number | null;
  setSafetyContourOverride: (val: number | null) => void;
  followMode: OwnShipFollowMode;
  setFollowMode: (mode: OwnShipFollowMode) => void;
}

export const useMapSettingsStore = create<MapSettingsState>()((set) => ({
  safetyContourOverride: null,
  setSafetyContourOverride: (val) => set({ safetyContourOverride: val }),
  followMode: 'SOFT',
  setFollowMode: (mode) => set({ followMode: mode }),
}));
