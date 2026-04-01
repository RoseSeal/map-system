/**
 * Targets Panel Component (Compact HUD Mode)
 * Added unread pulse animation and read-state management
 */

import {
  useRiskStore,
  useAiCenterStore,
  selectTargets,
  selectSelectedTarget,
  selectLatestLlmExplanations,
  selectReadLlmExplanations,
} from '../../store';
import { getRiskColor } from '../../config';
import { isLlmSource } from '../../utils/llmEventNormalizer';

export function TargetsPanel() {
  const targets = useRiskStore(selectTargets);
  const selectTarget = useRiskStore((state) => state.selectTarget);
  const selectedTarget = useRiskStore(selectSelectedTarget);

  const markLlmRead = useAiCenterStore((state) => state.markLlmRead);
  const requestAiCenterOpen = useAiCenterStore((state) => state.requestAiCenterOpen);
  const readLlmExplanations = useAiCenterStore(selectReadLlmExplanations);
  const latestLlmExplanations = useAiCenterStore(selectLatestLlmExplanations);

  if (targets.length === 0) {
    return null;
  }

  const selectedTargetLlmExplanation = selectedTarget ? latestLlmExplanations[selectedTarget.id] : null;
  const sortedTargets = [...targets].sort((a, b) => {
    const riskScore = { ALARM: 4, WARNING: 3, CAUTION: 2, SAFE: 1 };
    return (riskScore[b.risk_assessment.risk_level] || 0) - (riskScore[a.risk_assessment.risk_level] || 0);
  });

  return (
    <div className="bg-slate-950/70 backdrop-blur-xl rounded-lg p-3 text-white w-[280px] border border-white/10 shadow-2xl pointer-events-auto max-h-[50vh] flex flex-col">
      <div className="flex items-center justify-between border-b border-white/10 pb-2 mb-2">
        <h2 className="text-sm font-bold tracking-wide text-slate-100">周边目标</h2>
        <span className="bg-slate-800 px-1.5 py-0.5 rounded text-[10px] text-slate-400 font-mono">
          {targets.length} Tracked
        </span>
      </div>

      <div className="overflow-y-auto pr-1 space-y-2 scrollbar-thin">
        {sortedTargets.map((target) => {
          const riskColor = getRiskColor(target.risk_assessment.risk_level);
          const riskHex = `rgb(${riskColor.join(',')})`;
          const isSelected = selectedTarget?.id === target.id;
          const cachedLlmExplanation = latestLlmExplanations[target.id];
          const hasLlmExplanation = isSelected
            ? isLlmExplanation(selectedTargetLlmExplanation)
            : isLlmExplanation(cachedLlmExplanation);
          const isRead = readLlmExplanations[target.id];

          return (
            <div
              key={target.id}
              onClick={() => {
                selectTarget(target.id);
                if (hasLlmExplanation && !isRead) {
                  requestAiCenterOpen();
                  markLlmRead(target.id);
                }
              }}
              className={[
                'group p-2.5 rounded-md border cursor-pointer transition-all duration-200',
                isSelected
                  ? 'bg-slate-800/80 border-slate-400 shadow-[inset_0_0_15px_rgba(255,255,255,0.05)]'
                  : 'bg-slate-900/40 border-slate-800/60 hover:border-slate-600 hover:bg-slate-800/60',
              ].join(' ')}
              style={{ borderLeftColor: riskHex, borderLeftWidth: '3px' }}
            >
              <div className="flex justify-between items-center mb-1.5">
                <span className="text-xs font-mono font-medium text-slate-200">
                  ID: {target.id}
                </span>
                <span
                  className="text-[10px] font-bold px-1.5 py-0.5 rounded"
                  style={{ color: riskHex, backgroundColor: `rgba(${riskColor.join(',')}, 0.15)` }}
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
                  <span className={`font-mono ${target.risk_assessment.cpa_metrics.tcpa_sec < 300 ? 'text-orange-400 font-bold' : 'text-slate-200'}`}>
                    {(target.risk_assessment.cpa_metrics.tcpa_sec / 60).toFixed(1)} min
                  </span>
                </div>
              </div>

              {hasLlmExplanation && (
                <div className="mt-2.5 flex items-center">
                  {!isRead ? (
                    <span className="flex items-center gap-1.5 rounded-sm bg-cyan-500/10 px-1.5 py-0.5 border border-cyan-500/30">
                      <span className="relative flex h-1.5 w-1.5 shrink-0">
                        <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-cyan-400 opacity-75"></span>
                        <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-cyan-500 shadow-[0_0_5px_#06b6d4]"></span>
                      </span>
                      <span className="text-[10px] text-cyan-300 font-medium">新 AI 评估</span>
                    </span>
                  ) : (
                    <span className="flex items-center gap-1.5 rounded-sm bg-slate-800/50 px-1.5 py-0.5 border border-white/5">
                      <span className="h-1 w-1 rounded-full bg-slate-500"></span>
                      <span className="text-[10px] text-slate-500">AI 评估已读</span>
                    </span>
                  )}
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

function isLlmExplanation(explanation?: { source?: string; text?: string } | null): boolean {
  return Boolean(
    explanation?.text?.trim()
    && isLlmSource(explanation?.source)
  );
}
