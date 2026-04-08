import {
  useRiskStore,
  useAiCenterStore,
  selectTargets,
  selectSelectedTargetIds,
  selectExplanationsByTargetId,
} from '../../store';
import { getRiskColor } from '../../config';

export function TargetsPanel() {
  const targets = useRiskStore(selectTargets);
  const explanationsByTargetId = useRiskStore(selectExplanationsByTargetId);
  const selectTarget = useRiskStore((state) => state.selectTarget);
  const selectedTargetIds = useRiskStore(selectSelectedTargetIds);
  const requestAiCenterOpen = useAiCenterStore((state) => state.requestAiCenterOpen);

  if (targets.length === 0) {
    return null;
  }

  const sortedTargets = [...targets].sort((a, b) => {
    const riskScore = { ALARM: 4, WARNING: 3, CAUTION: 2, SAFE: 1 };
    return (riskScore[b.risk_assessment.risk_level] || 0) - (riskScore[a.risk_assessment.risk_level] || 0);
  });

  return (
    <div className="bg-white/90 dark:bg-slate-950/70 backdrop-blur-xl rounded-lg p-3 text-slate-800 dark:text-white w-[280px] border border-slate-200 dark:border-white/10 shadow-lg pointer-events-auto max-h-[50vh] flex flex-col transition-colors duration-300">
      <div className="flex items-center justify-between border-b border-slate-200 dark:border-white/10 pb-2 mb-2">
        <h2 className="text-sm font-bold tracking-wide text-slate-800 dark:text-slate-100">周边目标</h2>
        <span className="bg-slate-200 dark:bg-slate-800 px-1.5 py-0.5 rounded text-[10px] text-slate-500 dark:text-slate-400 font-mono">
          {targets.length} Tracked
        </span>
      </div>

      <div className="overflow-y-auto pr-1 space-y-2 scrollbar-thin">
        {sortedTargets.map((target) => {
          const riskColor = getRiskColor(target.risk_assessment.risk_level);
          const riskHex = `rgb(${riskColor.join(',')})`;
          const isSelected = selectedTargetIds.includes(target.id);
          const explanation = explanationsByTargetId[target.id];

          return (
            <div
              key={target.id}
              onClick={() => {
                selectTarget(target.id);
                if (explanation) {
                  requestAiCenterOpen();
                }
              }}
              className={[
                'group p-2.5 rounded-md border cursor-pointer transition-all duration-200',
                isSelected
                  ? 'bg-slate-100 dark:bg-slate-800/80 border-slate-400 dark:border-slate-400 shadow-[inset_0_0_15px_rgba(0,0,0,0.05)] dark:shadow-[inset_0_0_15px_rgba(255,255,255,0.05)]'
                  : 'bg-slate-50 dark:bg-slate-900/40 border-slate-200 dark:border-slate-800/60 hover:border-slate-400 dark:hover:border-slate-600 hover:bg-slate-100 dark:hover:bg-slate-800/60',
              ].join(' ')}
              style={{ borderLeftColor: riskHex, borderLeftWidth: '3px' }}
            >
              <div className="flex justify-between items-center mb-1.5">
                <span className="text-xs font-mono font-medium text-slate-700 dark:text-slate-200">
                  ID: {target.id}
                </span>
                <span
                  className="text-[10px] font-bold px-1.5 py-0.5 rounded"
                  style={{ color: riskHex, backgroundColor: `rgba(${riskColor.join(',')}, 0.15)` }}
                >
                  {translateRisk(target.risk_assessment.risk_level)}
                </span>
              </div>

              <div className="grid grid-cols-2 gap-2 text-[10px] text-slate-500 dark:text-slate-400">
                <div className="flex justify-between">
                  <span>距离</span>
                  <span className="text-slate-700 dark:text-slate-200 font-mono">
                    {target.risk_assessment.cpa_metrics.dcpa_nm.toFixed(2)} nm
                  </span>
                </div>
                <div className="flex justify-between">
                  <span>TCPA</span>
                  <span className={`font-mono ${target.risk_assessment.cpa_metrics.tcpa_sec < 300 ? 'text-orange-500 dark:text-orange-400 font-bold' : 'text-slate-700 dark:text-slate-200'}`}>
                    {(target.risk_assessment.cpa_metrics.tcpa_sec / 60).toFixed(1)} min
                  </span>
                </div>
              </div>

              {explanation && (
                <div className="mt-2.5 flex items-center">
                  <span className="flex items-center gap-1.5 rounded-sm bg-cyan-500/10 px-1.5 py-0.5 border border-cyan-500/30">
                    <span className="relative flex h-1.5 w-1.5 shrink-0">
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-cyan-400 opacity-75"></span>
                      <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-cyan-500 shadow-[0_0_5px_#06b6d4]"></span>
                    </span>
                    <span className="text-[10px] text-cyan-300 font-medium">{explanation.provider}</span>
                  </span>
                </div>
              )}
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
