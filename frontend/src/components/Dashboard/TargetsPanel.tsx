/**
 * Targets Panel Component (Compact HUD Mode)
 * Displays list of tracked targets and their risk levels
 * Localized for Chinese users
 */

import { useRiskStore, selectTargets, selectSelectedTarget } from '../../store';
import { getRiskColor, COLORS } from '../../config';

export function TargetsPanel() {
  const targets = useRiskStore(selectTargets);
  const selectTarget = useRiskStore((state) => state.selectTarget);
  const selectedTarget = useRiskStore(selectSelectedTarget);
  
  if (targets.length === 0) {
    return null;
  }
  
  // Sort by risk level (ALARM first)
  const sortedTargets = [...targets].sort((a, b) => {
    const riskScore = { ALARM: 4, WARNING: 3, CAUTION: 2, SAFE: 1 };
    return (riskScore[b.risk_assessment.risk_level] || 0) - (riskScore[a.risk_assessment.risk_level] || 0);
  });
  
  return (
    <div className="bg-slate-950/60 backdrop-blur-md rounded-md p-3 text-white w-[280px] border border-white/10 shadow-xl pointer-events-auto max-h-[40vh] flex flex-col">
      <div className="flex items-center justify-between border-b border-white/10 pb-2 mb-2">
        <h2 className="text-sm font-bold tracking-wide text-slate-100">目标追踪 (Targets)</h2>
        <span className="bg-slate-800 px-1.5 py-0.5 rounded text-[10px] text-slate-400 font-mono">
          {targets.length}
        </span>
      </div>
      
      <div className="overflow-y-auto pr-1 space-y-1.5 scrollbar-thin">
        {sortedTargets.map(target => {
          const riskColor = getRiskColor(target.risk_assessment.risk_level);
          const riskHex = `rgb(${riskColor.join(',')})`;
          const isSelected = selectedTarget?.id === target.id;
          
          return (
            <div 
              key={target.id}
              onClick={() => selectTarget(target.id)}
              className={`
                group p-2 rounded border cursor-pointer transition-all duration-200
                ${isSelected ? 'bg-slate-800 border-slate-500' : 'bg-slate-800/30 border-slate-800 hover:border-slate-600 hover:bg-slate-800/50'}
              `}
              style={{ borderLeftColor: riskHex, borderLeftWidth: '3px' }}
            >
              <div className="flex justify-between items-center mb-1">
                <span className="text-xs font-mono font-medium text-slate-200">
                  {target.id}
                </span>
                <span 
                  className="text-[10px] font-bold px-1 rounded"
                  style={{ color: riskHex, backgroundColor: `rgba(${riskColor.join(',')}, 0.1)` }}
                >
                  {translateRisk(target.risk_assessment.risk_level)}
                </span>
              </div>
              
              <div className="grid grid-cols-2 gap-2 text-[10px] text-slate-400">
                <div className="flex justify-between">
                   <span>距离</span>
                   <span className="text-slate-200 font-mono">
                     {target.risk_assessment.cpa_metrics.dcpa_nm.toFixed(2)} nm
                   </span>
                </div>
                <div className="flex justify-between">
                   <span>TCPA</span>
                   <span className={`font-mono ${target.risk_assessment.cpa_metrics.tcpa_sec < 300 ? 'text-orange-400' : 'text-slate-200'}`}>
                     {(target.risk_assessment.cpa_metrics.tcpa_sec / 60).toFixed(1)} min
                   </span>
                </div>
                <div className="flex justify-between">
                   <span>航速</span>
                   <span className="text-slate-200 font-mono">{target.vector.speed_kn.toFixed(1)} kn</span>
                </div>
                <div className="flex justify-between">
                   <span>航向</span>
                   <span className="text-slate-200 font-mono">{target.vector.course_deg.toFixed(0)}</span>
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function translateRisk(level: string): string {
  switch (level) {
    case 'SAFE': return '安全';
    case 'CAUTION': return '注意';
    case 'WARNING': return '警告';
    case 'ALARM': return '警报';
    default: return level;
  }
}