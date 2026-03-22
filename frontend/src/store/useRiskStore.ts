/**
 * Risk Store
 * Central state management for RiskObject data
 * Uses Zustand for high-frequency socket updates
 */
/**
 * useRiskStore 是页面级状态中心
 * 负责承接 WebSocket 输入的风险业务数据，同时维护连接状态和前端交互态
 * 并将部分核心业务字段扁平化/派生化，方便组件按需订阅和渲染。`
 */

// 目前同时存储服务端业务数据与前端UI数据，后续可以考虑拆分成两个store：一个专注业务数据，一个专注UI状态
import { create } from 'zustand'; // Zustand 是一个轻量的全局状态管理工具，用于管理和更新应用状态，特别适合频繁更新的场景，如WebSocket数据流
import { subscribeWithSelector } from 'zustand/middleware';
import type { RiskObject, OwnShip, Target, EnvironmentContext, Governance } from '../types/schema';
import { PERFORMANCE } from '../config/constants';

interface RiskState {
  // Core data
  riskObject: RiskObject | null; // 完整的RiskObject数据，直接从WebSocket接收并存储
  lastUpdateTime: number; // 上次更新的时间戳，用于计算数据新鲜度
  
  // Derived state for quick access
  ownShip: OwnShip | null;
  targets: Target[];       // Tracked targets (nearby - for panel)
  allTargets: Target[];    // All targets (for map rendering)
  governance: Governance | null;
  environment: EnvironmentContext | null;
  
  // Connection state
  isConnected: boolean;
  connectionError: string | null;
  
  // UI state
  isLowTrust: boolean;
  selectedTargetId: string | null;
  
  // Actions
  // 修改这份状态仓库的标准入口
  setRiskObject: (data: RiskObject) => void; // 写入新的风险数据
  setConnectionStatus: (connected: boolean, error?: string | null) => void; // 写连接状态
  selectTarget: (targetId: string | null) => void; // 记录当前选中的目标
  reset: () => void; // 恢复初始状态
}

// 默认值，确保状态结构完整，避免undefined错误
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

// 创建store实例
// 使用subscribeWithSelector中间件以支持高效的选择性订阅，避免不必要的组件重渲染
export const useRiskStore = create<RiskState>()(
  subscribeWithSelector((set) => ({
    ...initialState,

    /*
    * 把完整 data 存进 riskObject
    * 记录更新时间
    * 把里面常用字段拆出来
    * 计算一个派生状态 isLowTrust
    */
    setRiskObject: (data: RiskObject) => {
      set({
        riskObject: data,
        lastUpdateTime: Date.now(),
        ownShip: data.own_ship,
        targets: data.targets,
        allTargets: data.all_targets || data.targets, // Fallback to targets if all_targets not provided
        governance: data.governance,
        environment: data.environment_context,
        isLowTrust: data.governance.trust_factor < PERFORMANCE.LOW_TRUST_THRESHOLD,
      });
    },

    // 连接状态也统一放在store里，方便全局访问和UI更新
    setConnectionStatus: (connected: boolean, error: string | null = null) => {
      set({
        isConnected: connected,
        connectionError: error,
      });
    },

    // 记录当前选中的目标ID，UI组件可以订阅这个字段以显示目标详情
    selectTarget: (targetId: string | null) => {
      set({ selectedTargetId: targetId });
    },

    // 重置状态到初始值，方便调试或重新连接时清空旧数据
    reset: () => {
      set(initialState);
    },
  }))
);

// Selectors for optimized subscriptions
// 快捷读取函数
export const selectOwnShip = (state: RiskState) => state.ownShip;
export const selectTargets = (state: RiskState) => state.targets;
export const selectAllTargets = (state: RiskState) => state.allTargets;
export const selectGovernance = (state: RiskState) => state.governance;
export const selectEnvironment = (state: RiskState) => state.environment;
export const selectIsLowTrust = (state: RiskState) => state.isLowTrust;
export const selectIsConnected = (state: RiskState) => state.isConnected;
export const selectSelectedTarget = (state: RiskState) => {
  if (!state.selectedTargetId) return null;
  return state.targets.find(t => t.id === state.selectedTargetId) || null;
};
