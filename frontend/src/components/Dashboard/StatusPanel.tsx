/**
 * Status Panel Component (Compact HUD Mode)
 * Displays own ship status, trust factor, and connection state.
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
    <div className="grid grid-cols-[1fr_auto] items-center gap-2">
      <span className="text-[9px] uppercase font-mono text-slate-500 dark:text-slate-400 tracking-tighter text-right">{label}</span>
      <span
        className={`w-1.5 h-1.5 rounded-full ${state === 'reconnecting' ? 'animate-spin' : 'animate-pulse'}`}
        style={{ backgroundColor: dotColor }}
      />
    </div>
  );
}

export function StatusPanel() {
  const ownShip = useRiskStore(selectOwnShip);
  const governance = useRiskStore(selectGovernance);
  const riskConnectionState = useRiskStore(selectRiskConnectionState);
  const chatConnectionState = useAiCenterStore(selectChatConnectionState);
  const isLowTrust = useRiskStore(selectIsLowTrust);

  const containerStyle = "bg-white/95 dark:bg-slate-950/80 backdrop-blur-xl rounded-xl p-3 text-slate-800 dark:text-white w-[280px] border border-slate-200 dark:border-white/5 shadow-xl pointer-events-auto transition-all duration-300 space-y-2.5";

  if (!ownShip || !governance) {
    return (
      <div className={containerStyle}>
        <div className="flex items-center justify-between border-b border-slate-100 dark:border-white/5 pb-2">
          <h2 className="text-xs font-bold tracking-widest text-slate-400 uppercase">System Status</h2>
          <div className="flex flex-col items-end gap-1">
            <ConnectionIndicator label="STREAM" state={riskConnectionState} />
            <ConnectionIndicator label="AI-WS" state={chatConnectionState} />
          </div>
        </div>
        <div className="text-slate-400 dark:text-slate-500 text-center text-[10px] font-mono py-4 animate-pulse">
          INITIALIZING TELEMETRY...
        </div>
      </div>
    );
  }

  const healthColor = getHealthColor(ownShip.platform_health.status);

  return (
    <div className={containerStyle}>
      <div className="flex items-center justify-between border-b border-slate-100 dark:border-white/5 pb-2">
        <h2 className="text-xs font-bold tracking-widest text-slate-800 dark:text-slate-200 uppercase">本船态势</h2>
        <div className="flex flex-col items-end gap-1">
          <ConnectionIndicator label="STREAM" state={riskConnectionState} />
          <ConnectionIndicator label="AI-WS" state={chatConnectionState} />
        </div>
      </div>

      {isLowTrust && (
        <div className="bg-orange-500/10 border border-orange-500/20 rounded-lg px-2 py-1.5 flex items-center justify-center gap-2 animate-pulse">
          <span className="text-[10px] text-orange-600 dark:text-orange-400 font-bold uppercase tracking-wider">
            Trust Level Warning: {(governance.trust_factor * 100).toFixed(0)}%
          </span>
        </div>
      )}

      <div className="grid grid-cols-2 gap-3 pt-1">
        <div className="space-y-2">
          <label className="text-[9px] text-slate-400 uppercase font-bold tracking-widest">航行动力学</label>
          <div className="flex justify-between items-baseline">
            <span className="text-[10px] text-slate-500">SOG 航速</span>
            <span className="text-sm font-mono font-bold text-cyan-600 dark:text-cyan-400">{ownShip.dynamics.sog.toFixed(1)}<span className="text-[9px] ml-0.5 font-normal">kn</span></span>
          </div>
          <div className="flex justify-between items-baseline">
            <span className="text-[10px] text-slate-500">COG 航向</span>
            <span className="text-sm font-mono font-bold text-cyan-600 dark:text-cyan-400">{ownShip.dynamics.cog.toFixed(1)}°</span>
          </div>
          <div className="flex justify-between items-baseline">
            <span className="text-[10px] text-slate-500">HDG 艏向</span>
            <span className="text-sm font-mono text-slate-600 dark:text-slate-300">{ownShip.dynamics.hdg.toFixed(1)}°</span>
          </div>
        </div>

        <div className="space-y-2 pl-3 border-l border-slate-100 dark:border-white/5">
          <label className="text-[9px] text-slate-400 uppercase font-bold tracking-widest">平台系统</label>
          <div className="flex flex-col">
            <span className="text-[9px] text-slate-500 uppercase">经纬度位置</span>
            <span className="text-[10px] font-mono text-slate-600 dark:text-slate-300">E{ownShip.position.lon.toFixed(4)}</span>
            <span className="text-[10px] font-mono text-slate-600 dark:text-slate-300">N{ownShip.position.lat.toFixed(4)}</span>
          </div>
          <div className="mt-1 pt-1 border-t border-slate-100 dark:border-white/5 flex justify-between items-center">
            <span className="text-[9px] text-slate-500 uppercase">健康状态</span>
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
    case 'NORMAL': return '#10b981';
    case 'DEGRADED': return '#f59e0b';
    case 'NUC': return '#ef4444';
    default: return '#94a3b8';
  }
}

function mapHealthStatus(status: string): string {
  switch (status) {
    case 'NORMAL': return 'OK';
    case 'DEGRADED': return 'DEG';
    case 'NUC': return 'NUC';
    default: return status;
  }
}
