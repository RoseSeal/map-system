import {
  useRiskStore,
  useAiCenterStore,
  selectTargets,
  selectSelectedTargetIds,
  selectExplanationsByTargetId,
} from '../../store';
import { getRiskColor } from '../../config';
import {
  translateEncounterType,
  getRiskScoreBorderWidth,
  getRiskConfidenceOpacity,
} from '../../utils/riskDisplay';

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
    const levelScore = { ALARM: 4, WARNING: 3, CAUTION: 2, SAFE: 1 };
    const levelDiff = (levelScore[b.risk_assessment.risk_level] || 0) - (levelScore[a.risk_assessment.risk_level] || 0);
    if (levelDiff !== 0) {
      return levelDiff;
    }
    return (b.risk_assessment.risk_score ?? 0) - (a.risk_assessment.risk_score ?? 0);
  });

  return (
    <div className="bg-white/95 dark:bg-slate-950/80 backdrop-blur-xl rounded-xl p-3 text-slate-800 dark:text-white w-[280px] border border-slate-200 dark:border-white/5 shadow-2xl pointer-events-auto max-h-[60vh] flex flex-col transition-all duration-300">
      <div className="flex items-center justify-between border-b border-slate-100 dark:border-white/5 pb-2 mb-3">
        <h2 className="text-xs font-bold tracking-widest text-slate-800 dark:text-slate-200 uppercase">周边目标</h2>
        <span className="bg-slate-100 dark:bg-slate-800/50 px-2 py-0.5 rounded text-[10px] text-slate-500 dark:text-slate-400 font-mono font-bold">
          {targets.length} 已追踪
        </span>
      </div>

      <div className="overflow-y-auto pr-1 space-y-2.5 scrollbar-thin scrollbar-thumb-slate-200 dark:scrollbar-thumb-slate-800">
        {sortedTargets.map((target) => {
          const riskColor = getRiskColor(target.risk_assessment.risk_level);
          const riskHex = `rgb(${riskColor.join(',')})`;
          const isSelected = selectedTargetIds.includes(target.id);
          const explanation = explanationsByTargetId[target.id];
          const encounterTypeText = translateEncounterType(target.risk_assessment.encounter_type);

          const isLowConfidence = target.risk_assessment.risk_confidence !== undefined && target.risk_assessment.risk_confidence < 0.5;

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
                'group p-3 rounded-lg border cursor-pointer transition-all duration-300 ease-in-out',
                isSelected
                  ? 'bg-slate-100 dark:bg-cyan-500/10 border-slate-300 dark:border-cyan-500/30 shadow-inner'
                  : 'bg-slate-50/50 dark:bg-slate-900/40 border-slate-100 dark:border-white/5 hover:border-slate-300 dark:hover:border-white/10',
              ].join(' ')}
              style={{
                borderLeftColor: riskHex,
                borderLeftWidth: `${getRiskScoreBorderWidth(target.risk_assessment.risk_score)}px`,
                opacity: getRiskConfidenceOpacity(target.risk_assessment.risk_confidence),
              }}
            >
              <div className="flex justify-between items-center mb-2 gap-2">
                <div className="flex items-center gap-1.5 min-w-0">
                  <span className="text-[11px] font-mono font-bold text-slate-700 dark:text-slate-200 truncate">
                    ID: {target.id}
                  </span>
                  {target.risk_assessment.risk_level !== 'SAFE' && encounterTypeText && (
                    <span className="text-[8px] px-1.5 py-0.5 rounded bg-slate-200 dark:bg-slate-800 text-slate-500 dark:text-slate-400 font-bold uppercase tracking-tighter">
                      {encounterTypeText}
                    </span>
                  )}
                </div>
                <span
                  className="text-[9px] font-bold px-1.5 py-0.5 rounded tracking-tighter shrink-0"
                  style={{ color: riskHex, backgroundColor: `rgba(${riskColor.join(',')}, 0.15)` }}
                >
                  {translateRisk(target.risk_assessment.risk_level)}
                </span>
              </div>

              {/* 低置信度提示框 */}
              {isLowConfidence && (
                <div className="mb-2 bg-orange-500/10 border border-orange-500/20 rounded px-1.5 py-1 flex items-center gap-2 animate-pulse">
                  <span className="w-1 h-1 rounded-full bg-orange-500"></span>
                  <span className="text-[8px] text-orange-600 dark:text-orange-400 font-bold uppercase tracking-tight">
                    低置信度评估 ({(target.risk_assessment.risk_confidence! * 100).toFixed(0)}%)
                  </span>
                </div>
              )}

              <div className="grid grid-cols-2 gap-x-3 gap-y-1 text-[10px]">
                <div className="flex justify-between border-r border-slate-200 dark:border-white/5 pr-2">
                  <span className="text-slate-400 uppercase text-[8px] font-bold">距离</span>
                  <span className="text-slate-700 dark:text-slate-300 font-mono font-medium">
                    {target.risk_assessment.cpa_metrics.dcpa_nm.toFixed(2)}<span className="text-[8px] ml-0.5">nm</span>
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-400 uppercase text-[8px] font-bold">碰撞时间</span>
                  <span className={`font-mono font-medium ${target.risk_assessment.cpa_metrics.tcpa_sec < 300 ? 'text-orange-500 dark:text-orange-400 font-bold' : 'text-slate-700 dark:text-slate-300'}`}>
                    {(target.risk_assessment.cpa_metrics.tcpa_sec / 60).toFixed(1)}<span className="text-[8px] ml-0.5">min</span>
                  </span>
                </div>
              </div>

              {explanation && (
                <div className="mt-2 flex items-center gap-2">
                  <div className="flex-1 h-[1px] bg-slate-200 dark:bg-white/5"></div>
                  <span className="flex items-center gap-1 px-1.5 py-0.5 rounded-sm bg-cyan-500/10 border border-cyan-500/20">
                    <span className="relative flex h-1 w-1">
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-cyan-400 opacity-75"></span>
                      <span className="relative inline-flex rounded-full h-1 w-1 bg-cyan-500"></span>
                    </span>
                    <span className="text-[8px] text-cyan-600 dark:text-cyan-400 font-bold uppercase">AI 已激活</span>
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
