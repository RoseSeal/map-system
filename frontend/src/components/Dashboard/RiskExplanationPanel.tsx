/**
 * Risk Explanation Panel (Magnetic Event Log Feed)
 * - Removed early return to keep the magnetic handle visible.
 * - Added Empty State for no-risk scenarios.
 * - Extracted PANEL_WIDTH for easy visual tweaking.
 */
import { useState, useRef, useEffect } from 'react';
import { useRiskStore, selectLlmExplainedHighRiskTargets, selectSelectedTarget } from '../../store';
import { getRiskColor } from '../../config';

// --------------------------------------------------------
// 🎨 在这里调整面板大小！
// 建议区间：280 (紧凑) ~ 400 (宽大，适合长文本)
const PANEL_WIDTH = 280; 
// 建议区间：60vh (紧凑) ~ 85vh (高屏沉浸)
const PANEL_HEIGHT = '62vh';
// --------------------------------------------------------

export function RiskExplanationPanel() {
  const explainedTargets = useRiskStore(selectLlmExplainedHighRiskTargets);
  const selectedTarget = useRiskStore(selectSelectedTarget);
  const selectTarget = useRiskStore((state) => state.selectTarget);
  const markLlmRead = useRiskStore((state) => state.markLlmRead);
  
  // 磁吸状态控制
  const [isHovered, setIsHovered] = useState(false);
  const timeoutRef = useRef<ReturnType<typeof setTimeout>>();

  const handleMouseEnter = () => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    setIsHovered(true);
  };

  const handleMouseLeave = () => {
    timeoutRef.current = setTimeout(() => {
      setIsHovered(false);
    }, 300);
  };

  // 自动展开逻辑：如果当前选中的目标有 AI 解释，自动弹出抽屉
  useEffect(() => {
    if (selectedTarget && explainedTargets.some(t => t.id === selectedTarget.id)) {
      handleMouseEnter();
    }
  }, [selectedTarget, explainedTargets]);

  return (
    <div
      // 把平移动画提升到最外层容器，并去掉 tailwind 里的 -translate-y-1/2，交由 style 统一接管 transform
      className="fixed right-0 top-1/2 flex pointer-events-none z-50 transition-transform duration-500 ease-[cubic-bezier(0.16,1,0.3,1)]"
      style={{ 
        height: PANEL_HEIGHT,
        transform: isHovered 
          ? 'translateY(-50%) translateX(0)' 
          : `translateY(-50%) translateX(${PANEL_WIDTH}px)`
      }}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
    >
      {/* 磁吸感应区 (隐形/半透明把手) - 永远渲染 */}
      <div className="w-8 h-full pointer-events-auto flex items-center justify-end pr-1 cursor-w-resize group">
        <div 
          className={`w-1 h-20 rounded-full transition-all duration-300 ${
            isHovered 
              ? 'bg-cyan-500/60 shadow-[0_0_8px_rgba(6,182,212,0.4)]' 
              : 'bg-white/10 group-hover:bg-cyan-400/80 group-hover:h-24'
          }`} 
        />
      </div>

      {/* 主抽屉面板 */}
      <div 
        // 内部面板不再负责平移，剥离 transform 逻辑
        className="h-full bg-slate-950/80 backdrop-blur-xl border-l border-white/10 shadow-2xl rounded-l-2xl flex flex-col pointer-events-auto"
        style={{ width: `${PANEL_WIDTH}px` }}
      >
        <div className="px-4 py-3 border-b border-white/10 shrink-0 bg-gradient-to-r from-transparent to-cyan-950/20">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-bold tracking-wide text-slate-100 flex items-center gap-2">
              {explainedTargets.length > 0 && (
                <span className="w-2 h-2 rounded-full bg-cyan-400 animate-pulse"></span>
              )}
              AI 态势监控日志
            </h2>
            <span className="text-[10px] bg-slate-800 text-slate-400 px-1.5 py-0.5 rounded font-mono">
              {explainedTargets.length} 个警告
            </span>
          </div>
        </div>

        {/* 日志流列表区 */}
        <div className="flex-1 overflow-y-auto p-3 space-y-3 scrollbar-thin relative">
          {/* 空状态 (Empty State) 兜底渲染 */}
          {explainedTargets.length === 0 ? (
            <div className="absolute inset-0 flex flex-col items-center justify-center text-slate-500 opacity-60">
              <span className="text-3xl mb-3">🛡️</span>
              <span className="text-xs tracking-wider">当前航区暂无高危 AI 评估</span>
            </div>
          ) : (
            explainedTargets.map((target) => {
              const isSelected = selectedTarget?.id === target.id;
              const riskColor = getRiskColor(target.risk_assessment.risk_level);
              const riskHex = `rgb(${riskColor.join(',')})`;
              const llmText = useRiskStore.getState().latestLlmExplanations[target.id]?.text;

              return (
                <div 
                  key={target.id}
                  onClick={() => {
                    selectTarget(target.id);
                    markLlmRead(target.id);
                  }}
                  className={`p-3 rounded-lg border transition-all duration-200 cursor-pointer ${
                    isSelected 
                      ? 'border-cyan-500/50 bg-cyan-950/30 shadow-[inset_0_0_20px_rgba(6,182,212,0.05)]' 
                      : 'border-white/5 bg-slate-900/50 hover:bg-slate-800/80'
                  }`}
                >
                  <div className="flex justify-between items-center mb-2">
                    <div className="flex items-center gap-2">
                      <span className="font-mono text-xs text-slate-200">ID: {target.id}</span>
                    </div>
                    <span
                      className="text-[9px] font-bold px-1.5 py-0.5 rounded uppercase"
                      style={{ color: riskHex, backgroundColor: `rgba(${riskColor.join(',')}, 0.15)` }}
                    >
                      {target.risk_assessment.risk_level}
                    </span>
                  </div>
                  
                  <div className="text-[12px] leading-relaxed text-slate-300 whitespace-pre-wrap font-medium">
                    {llmText}
                  </div>
                  
                  <div className="mt-2 pt-2 border-t border-white/5 flex justify-between items-center">
                    <span className="text-[9px] text-slate-500">Source: LLM Agent</span>
                    {isSelected && <span className="text-[9px] text-cyan-400">正在追踪</span>}
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}