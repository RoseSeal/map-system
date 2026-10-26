import { create } from 'zustand';
import type { AtonFeatureCollection, NoticeAlertPayload, PlannedRoute } from '../types/schema';
import { fetchActiveRoute, fetchAtonNotices } from '../services/navigationService';
import { riskSseService } from '../services/riskSseService';

const RECENT_ALERT_LIMIT = 5;

export interface NavigationState {
  route: PlannedRoute | null;
  atons: AtonFeatureCollection | null;
  selectedAtonId: string | null;
  activeAlerts: Record<string, NoticeAlertPayload>;
  recentAlerts: NoticeAlertPayload[];
  loadError: string | null;

  loadNavigationData: () => Promise<void>;
  selectAton: (atonId: string | null) => void;
  applyNoticeAlert: (payload: NoticeAlertPayload) => void;
  resetNoticeAlerts: () => void;
}

export const useNavigationStore = create<NavigationState>((set) => ({
  route: null,
  atons: null,
  selectedAtonId: null,
  activeAlerts: {},
  recentAlerts: [],
  loadError: null,

  loadNavigationData: async () => {
    try {
      const [route, atons] = await Promise.all([fetchActiveRoute(), fetchAtonNotices()]);
      set({ route, atons, loadError: null });
    } catch (error) {
      set({ loadError: error instanceof Error ? error.message : String(error) });
    }
  },

  selectAton: (atonId) => set({ selectedAtonId: atonId }),

  applyNoticeAlert: (payload) => {
    set((state) => {
      if (payload.status === 'ACTIVE') {
        return { activeAlerts: { ...state.activeAlerts, [payload.alert_id]: payload } };
      }
      const rest = { ...state.activeAlerts };
      delete rest[payload.alert_id];
      return {
        activeAlerts: rest,
        recentAlerts: [payload, ...state.recentAlerts].slice(0, RECENT_ALERT_LIMIT),
      };
    });
  },

  resetNoticeAlerts: () => set({ activeAlerts: {} }),
}));

export const selectPlannedRoute = (state: NavigationState) => state.route;
export const selectAtonCollection = (state: NavigationState) => state.atons;
export const selectSelectedAtonId = (state: NavigationState) => state.selectedAtonId;
export const selectActiveNoticeAlerts = (state: NavigationState) => state.activeAlerts;

let hasInitializedNavigationSubscriptions = false;

function initializeNavigationSubscriptions(): void {
  if (hasInitializedNavigationSubscriptions) {
    return;
  }
  hasInitializedNavigationSubscriptions = true;
  riskSseService.onNoticeAlert((payload) => {
    useNavigationStore.getState().applyNoticeAlert(payload);
  });
  // NOTICE_ALERT 不进入 replay 缓存，断线期间可能错过 CLEARED。连接建立或断开时清空活动提醒，
  // 仍在生效的提醒由后端下一次刷新（refresh-seconds）重新下发。
  riskSseService.onConnectionStatusChange((state) => {
    if (state === 'connected' || state === 'disconnected') {
      useNavigationStore.getState().resetNoticeAlerts();
    }
  });
}

initializeNavigationSubscriptions();
