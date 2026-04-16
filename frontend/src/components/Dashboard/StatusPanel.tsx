/**
 * Status Panel Component (Compact HUD Mode)
 * Displays own ship status, trust factor, and connection state.
 * Global settings (theme, speech) are handled by ToolbarOverlay.
 */

import {
  useRiskStore,
  useAiCenterStore,
  selectOwnShip,
  selectGovernance,
  selectRiskConnectionState,
  selectChatConnectionState,
  selectIsLowTrust,
} from '../../store';
import type { DisplayConnectionState } from '../../types/connection';

function ConnectionIndicator({ label, state }: { label: string; state: DisplayConnectionState }) {
  const dotColor = state === 'connected' ? '#4ade80' : state === 'reconnecting' ? '#facc15' : '#ef4444';
  return (
    <div className="flex items-center gap-1.5">
      <span
        className={`w-1.5 h-1.5 rounded-full ${state === 'reconnecting' ? 'animate-spin' : 'animate-pulse'}`}
        style={{ backgroundColor: dotColor }}
      />
      <span className="text-[10px] uppercase font-mono text-slate-500 dark:text-slate-400">{label}</span>
    </div>
  );
}

export function StatusPanel() {
  const ownShip = useRiskStore(selectOwnShip);
  const governance = useRiskStore(selectGovernance);
  const riskConnectionState = useRiskStore(selectRiskConnectionState);
  const chatConnectionState = useAiCenterStore(selectChatConnectionState);
  const isLowTrust = useRiskStore(selectIsLowTrust);

  if (!ownShip || !governance) {
    return (
      <div className="bg-white/90 dark:bg-slate-950/60 backdrop-blur-md rounded-md p-3 text-slate-800 dark:text-white w-[280px] border border-slate-200 dark:border-white/10 shadow-lg pointer-events-auto transition-colors duration-300 space-y-2">
        <div className="flex items-center justify-between border-b border-slate-200 dark:border-white/10 pb-2">
          <h2 className="text-sm font-bold tracking-wide text-slate-800 dark:text-slate-100">本船状态</h2>
          <div className="flex flex-col items-start gap-1">
            <ConnectionIndicator label="实时消息" state={riskConnectionState} />
            <ConnectionIndicator label="AI 助手" state={chatConnectionState} />
          </div>        </div>
        <div className="text-slate-500 dark:text-slate-400 text-center text-[10px] font-mono py-2">
          等待数据初始化...
        </div>
      </div>
    );
  }

  const healthColor = getHealthColor(ownShip.platform_health.status);

  return (
    <div className="bg-white/90 dark:bg-slate-950/60 backdrop-blur-md rounded-md p-3 text-slate-800 dark:text-white w-[280px] border border-slate-200 dark:border-white/10 shadow-lg pointer-events-auto space-y-2 transition-colors duration-300">
      <div className="flex items-center justify-between border-b border-slate-200 dark:border-white/10 pb-2">
        <h2 className="text-sm font-bold tracking-wide text-slate-800 dark:text-slate-100">本船状态</h2>
        <div className="flex flex-col items-start gap-1">
          <ConnectionIndicator label="实时消息" state={riskConnectionState} />
          <ConnectionIndicator label="AI 助手" state={chatConnectionState} />
        </div>
      </div>

      {isLowTrust && (
        <div className="bg-orange-100 dark:bg-orange-500/20 border border-orange-400 dark:border-orange-500/50 rounded px-2 py-1 flex items-center gap-2 animate-pulse transition-colors duration-300">
          <span className="text-[10px] text-orange-800 dark:text-orange-200 font-bold">
            低置信度（{(governance.trust_factor * 100).toFixed(0)}%）
          </span>
        </div>
      )}

      <div className="grid grid-cols-2 gap-2 pt-1">
        <div className="space-y-1">
          <label className="text-[10px] text-slate-500 uppercase font-bold">航行</label>
          <div className="flex justify-between items-baseline">
            <span className="text-[10px] text-slate-500 dark:text-slate-400">航速</span>
            <span className="text-sm font-mono text-cyan-600 dark:text-cyan-400">{ownShip.dynamics.sog.toFixed(1)} <span className="text-[9px]">kn</span></span>
          </div>
          <div className="flex justify-between items-baseline">
            <span className="text-[10px] text-slate-500 dark:text-slate-400">COG</span>
            <span className="text-sm font-mono text-cyan-600 dark:text-cyan-400">{ownShip.dynamics.cog.toFixed(1)}</span>
          </div>
          <div className="flex justify-between items-baseline">
            <span className="text-[10px] text-slate-500 dark:text-slate-400">HDG</span>
            <span className="text-sm font-mono text-slate-700 dark:text-slate-300">{ownShip.dynamics.hdg.toFixed(1)}</span>
          </div>
        </div>

        <div className="space-y-1 pl-2 border-l border-slate-300 dark:border-white/10">
          <label className="text-[10px] text-slate-500 uppercase font-bold">系统</label>
          <div className="flex justify-between items-baseline">
            <span className="text-[10px] text-slate-500 dark:text-slate-400">经度</span>
            <span className="text-[10px] font-mono text-slate-700 dark:text-slate-300">E {ownShip.position.lon.toFixed(3)}</span>
          </div>
          <div className="flex justify-between items-baseline">
            <span className="text-[10px] text-slate-500 dark:text-slate-400">纬度</span>
            <span className="text-[10px] font-mono text-slate-700 dark:text-slate-300">N {ownShip.position.lat.toFixed(3)}</span>
          </div>
          <div className="mt-1 pt-1 border-t border-slate-300 dark:border-white/10 flex justify-between items-center">
            <span className="text-[10px] text-slate-500 dark:text-slate-400">健康状态</span>
            <span className="text-[10px] font-bold" style={{ color: healthColor }}>
              {mapHealthStatus(ownShip.platform_health.status)}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}

function getHealthColor(status: string): string {
  switch (status) {
    case 'NORMAL': return '#4ade80';
    case 'DEGRADED': return '#facc15';
    case 'NUC': return '#ef4444';
    default: return '#94a3b8';
  }
}

function mapHealthStatus(status: string): string {
  switch (status) {
    case 'NORMAL': return '正常';
    case 'DEGRADED': return '降级';
    case 'NUC': return '失去控制';
    default: return status;
  }
}
