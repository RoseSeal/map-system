import { useState } from 'react';
import { useRiskStore, selectSelectedTarget, selectSelectedTargetLlmExplanation } from '../../store';
import { getRiskColor } from '../../config';

const PANEL_WIDTH = 320;
const VISIBLE_TAB_WIDTH = 44;

export function RiskExplanationPanel() {
  const selectedTarget = useRiskStore(selectSelectedTarget);
  const llmExplanation = useRiskStore(selectSelectedTargetLlmExplanation);
  const [collapsed, setCollapsed] = useState(false);

  const translateX = collapsed ? PANEL_WIDTH - VISIBLE_TAB_WIDTH : 0;
  const riskColor = selectedTarget ? getRiskColor(selectedTarget.risk_assessment.risk_level) : null;
  const riskHex = riskColor ? `rgb(${riskColor.join(',')})` : '#38bdf8';

  return (
    <div
      className="pointer-events-auto flex transition-transform duration-300 ease-out"
      style={{ width: `${PANEL_WIDTH}px`, transform: `translateX(${translateX}px)` }}
    >
      <button
        type="button"
        onClick={() => setCollapsed((value) => !value)}
        className="h-28 w-11 shrink-0 rounded-l-md border border-white/10 border-r-0 bg-slate-950/85 px-2 text-[11px] font-medium tracking-wide text-slate-200 shadow-xl backdrop-blur-md transition hover:border-cyan-400/50 hover:text-white"
      >
        <span className="block leading-4">{collapsed ? '展开说明' : '收起说明'}</span>
      </button>

      <div className="w-[276px] rounded-r-md border border-white/10 bg-slate-950/70 p-3 text-white shadow-xl backdrop-blur-md">
        <div className="flex items-center justify-between border-b border-white/10 pb-2">
          <div>
            <h2 className="text-sm font-bold tracking-wide text-slate-100">大模型风险说明</h2>
            {selectedTarget ? (
              <div className="mt-1 text-[10px] font-mono text-slate-400">目标 {selectedTarget.id}</div>
            ) : (
              <div className="mt-1 text-[10px] text-slate-500">未选择目标</div>
            )}
          </div>
          {selectedTarget && !collapsed && (
            <span
              className="text-[10px] font-bold px-1.5 py-0.5 rounded"
              style={{ color: riskHex, backgroundColor: `rgba(${riskColor!.join(',')}, 0.15)` }}
            >
              {translateRisk(selectedTarget.risk_assessment.risk_level)}
            </span>
          )}
        </div>

        {!collapsed && (
          <div className="mt-3 space-y-3">
            <div className="grid grid-cols-[64px_1fr] gap-y-2 text-sm">
              <span className="text-slate-500">目标编号</span>
              <span className="font-mono text-slate-200">{selectedTarget?.id || '--'}</span>

              <span className="text-slate-500">说明来源</span>
              <span className="text-slate-200">大模型</span>
            </div>

            <div className="rounded border border-white/10 bg-slate-900/60 p-3">
              <div className="text-[11px] tracking-wide text-slate-500">说明内容</div>
              <div className="mt-2 max-h-[36vh] overflow-y-auto pr-1 text-sm leading-6 text-slate-200 whitespace-pre-wrap">
                {renderExplanationText(selectedTarget?.id, llmExplanation?.text)}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function renderExplanationText(targetId?: string, text?: string): string {
  if (!targetId) {
    return '请选择一个目标查看大模型风险说明';
  }

  if (!text?.trim()) {
    return '当前目标暂无大模型风险说明';
  }

  return text;
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
