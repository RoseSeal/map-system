/**
 * Status Panel Component (Compact HUD Mode)
 * Displays own ship status, trust factor, and connection state
 */

import { useRiskStore, selectOwnShip, selectGovernance, selectIsConnected, selectIsLowTrust } from '../../store';
import { COLORS } from '../../config';

export function StatusPanel() {
  const ownShip = useRiskStore(selectOwnShip);
  const governance = useRiskStore(selectGovernance);
  const isConnected = useRiskStore(selectIsConnected);
  const isLowTrust = useRiskStore(selectIsLowTrust);

  if (!ownShip || !governance) {
    return (
      <div className="bg-slate-950/60 backdrop-blur-md rounded-md p-3 text-white w-[280px] border border-white/10 shadow-xl pointer-events-auto">
        <div className="text-slate-400 text-center text-sm font-mono">
          {isConnected ? 'Waiting for data...' : 'Offline'}
        </div>
      </div>
    );
  }

  const healthColor = getHealthColor(ownShip.platform_health.status);

  return (
    <div className="bg-slate-950/60 backdrop-blur-md rounded-md p-3 text-white w-[280px] border border-white/10 shadow-xl pointer-events-auto space-y-2">
      <div className="flex items-center justify-between border-b border-white/10 pb-2">
        <h2 className="text-sm font-bold tracking-wide text-slate-100">Own Ship</h2>
        <div className="flex items-center gap-1.5">
          <span
            className="w-1.5 h-1.5 rounded-full shadow-[0_0_8px_rgba(0,255,0,0.5)] animate-pulse"
            style={{ backgroundColor: isConnected ? COLORS.SAFE : COLORS.ALARM }}
          />
          <span className="text-[10px] uppercase font-mono text-slate-400">
            {isConnected ? 'Online' : 'Offline'}
          </span>
        </div>
      </div>

      {isLowTrust && (
        <div className="bg-orange-500/20 border border-orange-500/50 rounded px-2 py-1 flex items-center gap-2 animate-pulse">
          <span className="text-[10px] text-orange-200 font-medium">
            Low Trust ({(governance.trust_factor * 100).toFixed(0)}%)
          </span>
        </div>
      )}

      <div className="grid grid-cols-2 gap-2 pt-1">
        <div className="space-y-1">
          <label className="text-[10px] text-slate-500 uppercase font-bold">Nav</label>
          <div className="flex justify-between items-baseline">
            <span className="text-[10px] text-slate-400">Speed</span>
            <span className="text-sm font-mono text-cyan-400">{ownShip.dynamics.sog.toFixed(1)} <span className="text-[9px]">kn</span></span>
          </div>
          <div className="flex justify-between items-baseline">
            <span className="text-[10px] text-slate-400">COG</span>
            <span className="text-sm font-mono text-cyan-400">{ownShip.dynamics.cog.toFixed(1)}</span>
          </div>
          <div className="flex justify-between items-baseline">
            <span className="text-[10px] text-slate-400">HDG</span>
            <span className="text-sm font-mono text-slate-300">{ownShip.dynamics.hdg.toFixed(1)}</span>
          </div>
        </div>

        <div className="space-y-1 pl-2 border-l border-white/10">
          <label className="text-[10px] text-slate-500 uppercase font-bold">Sys</label>
          <div className="flex justify-between items-baseline">
            <span className="text-[10px] text-slate-400">Lon</span>
            <span className="text-[10px] font-mono text-slate-300">E {ownShip.position.lon.toFixed(3)}</span>
          </div>
          <div className="flex justify-between items-baseline">
            <span className="text-[10px] text-slate-400">Lat</span>
            <span className="text-[10px] font-mono text-slate-300">N {ownShip.position.lat.toFixed(3)}</span>
          </div>
          <div className="mt-1 pt-1 border-t border-white/10 flex justify-between items-center">
            <span className="text-[10px] text-slate-400">Health</span>
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
    case 'NORMAL': return 'Normal';
    case 'DEGRADED': return 'Degraded';
    case 'NUC': return 'NUC';
    default: return status;
  }
}
