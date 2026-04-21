import { create } from 'zustand';

interface MapSettingsState {
  safetyContourOverride: number | null;
  setSafetyContourOverride: (val: number | null) => void;
}

export const useMapSettingsStore = create<MapSettingsState>()((set) => ({
  safetyContourOverride: null,
  setSafetyContourOverride: (val) => set({ safetyContourOverride: val }),
}));
