import {
  selectExplanationsByTargetId,
  selectSelectedTargetIds,
  selectTargets,
  useAiCenterStore,
  useRiskStore,
} from '../../store';
import { useThemeStore } from '../../store/useThemeStore';
import { translateEncounterType } from '../../utils/riskDisplay';
import { CpaArc } from './CpaArc';

const riskColors: Record<string, string> = {
  SAFE: 'oklch(0.76 0.11 158)',
  CAUTION: 'oklch(0.82 0.12 85)',
  WARNING: 'oklch(0.72 0.15 55)',
  ALARM: 'oklch(0.66 0.18 22)',
};

const riskLabels: Record<string, string> = {
  SAFE: '安全',
  CAUTION: '注意',
  WARNING: '警告',
  ALARM: '警报',
};

const levelScore: Record<string, number> = {
  ALARM: 4,
  WARNING: 3,
  CAUTION: 2,
  SAFE: 1,
};

export function TargetsPanel() {
  const targets = useRiskStore(selectTargets);
  const explanationsByTargetId = useRiskStore(selectExplanationsByTargetId);
  const selectedTargetIds = useRiskStore(selectSelectedTargetIds);
  const selectTarget = useRiskStore((state) => state.selectTarget);
  const requestAiCenterOpen = useAiCenterStore((state) => state.requestAiCenterOpen);
  const { isDarkMode } = useThemeStore();

  if (targets.length === 0) {
    return null;
  }

  const sortedTargets = [...targets].sort((a, b) => {
    const levelDiff = (levelScore[b.risk_assessment.risk_level] ?? 0)
      - (levelScore[a.risk_assessment.risk_level] ?? 0);

    if (levelDiff !== 0) {
      return levelDiff;
    }

    return (b.risk_assessment.risk_score ?? 0) - (a.risk_assessment.risk_score ?? 0);
  });

  const counts = targets.reduce<Record<string, number>>((accumulator, target) => {
    const level = target.risk_assessment.risk_level;
    accumulator[level] = (accumulator[level] ?? 0) + 1;
    return accumulator;
  }, {});

  const glassClass = isDarkMode ? 'glass-vision-dark' : 'glass-vision';

  return (
    <div
      className={`${glassClass} anim-rise flex min-h-0 w-full flex-1 flex-col overflow-hidden rounded-[22px]`}
      style={{ animationDelay: '60ms' }}
    >
      <div className="shrink-0 px-5 pb-3 pt-4">
        <div className="mb-3 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <svg
              className="h-3.5 w-3.5"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              style={{ color: 'var(--accent)' }}
            >
              <circle cx="12" cy="12" r="9" />
              <circle cx="12" cy="12" r="4" />
              <path d="M12 3v2M12 19v2M3 12h2M19 12h2" />
            </svg>
            <span className="text-[13px] font-semibold" style={{ color: 'var(--ink-700)' }}>
              周边目标
            </span>
          </div>
          <span
            className="tnum rounded-full px-2 py-0.5 font-mono text-[10px]"
            style={{
              background: 'color-mix(in oklch, var(--ink-500) 10%, transparent)',
              color: 'var(--ink-700)',
            }}
          >
            {targets.length} 已追踪
          </span>
        </div>

        <div
          className="flex h-1 overflow-hidden rounded-full"
          style={{ background: 'color-mix(in oklch, var(--ink-500) 10%, transparent)' }}
        >
          {(['SAFE', 'CAUTION', 'WARNING', 'ALARM'] as const).map((level) => (
            counts[level] ? (
              <div
                key={level}
                className="h-full transition-all duration-500"
                style={{ flex: counts[level], background: riskColors[level] }}
              />
            ) : null
          ))}
        </div>
      </div>

      <div className="scrollbar-apple flex-1 min-h-0 space-y-2 overflow-y-auto px-3 pb-3">
        {sortedTargets.map((target) => {
          const {
            cpa_metrics: cpaMetrics,
            encounter_type: encounterType,
            risk_confidence: riskConfidence,
            risk_level: riskLevel,
          } = target.risk_assessment;
          const isSelected = selectedTargetIds.includes(target.id);
          const hasExplanation = Boolean(explanationsByTargetId[target.id]);
          const lowConfidence = riskConfidence !== undefined && riskConfidence < 0.5;
          const color = riskColors[riskLevel] ?? riskColors.SAFE;
          const encounterLabel = translateEncounterType(encounterType);

          return (
            <button
              key={target.id}
              type="button"
              onClick={() => {
                selectTarget(target.id);
                if (hasExplanation) {
                  requestAiCenterOpen();
                }
              }}
              className="group w-full rounded-2xl p-3 text-left transition-all duration-300 ease-out"
              style={{
                background: isSelected
                  ? `color-mix(in oklch, ${color} 9%, ${isDarkMode ? 'rgba(15,23,42,0.7)' : 'rgba(255,255,255,0.92)'})`
                  : isDarkMode
                    ? 'rgba(255,255,255,0.03)'
                    : 'rgba(255,255,255,0.5)',
                border: `0.5px solid ${isSelected
                  ? `color-mix(in oklch, ${color} 40%, transparent)`
                  : isDarkMode
                    ? 'rgba(255,255,255,0.05)'
                    : 'rgba(15,23,42,0.05)'}`,
                boxShadow: isSelected
                  ? `0 3px 12px -4px color-mix(in oklch, ${color} 28%, transparent)`
                  : 'none',
                transform: isSelected ? 'translateY(-1px)' : '',
              }}
            >
              <div className="flex items-start gap-3">
                <CpaArc
                  tcpa_sec={cpaMetrics.tcpa_sec}
                  dcpa_nm={cpaMetrics.dcpa_nm}
                  riskLevel={riskLevel}
                />

                <div className="min-w-0 flex-1">
                  <div className="mb-1.5 flex items-center justify-between gap-2">
                    <div className="flex min-w-0 items-center gap-1.5">
                      <span
                        className="tnum truncate font-mono text-[12px] font-semibold"
                        style={{ color: 'var(--ink-900)' }}
                      >
                        {target.id}
                      </span>
                      {riskLevel !== 'SAFE' && encounterLabel && (
                        <span
                          className="shrink-0 rounded px-1.5 py-0.5 text-[8px] font-medium"
                          style={{
                            background: 'color-mix(in oklch, var(--ink-500) 10%, transparent)',
                            color: 'var(--ink-500)',
                          }}
                        >
                          {encounterLabel}
                        </span>
                      )}
                    </div>
                    <span
                      className="shrink-0 rounded-full px-2 py-0.5 text-[9px] font-semibold tracking-wide"
                      style={{
                        color,
                        background: `color-mix(in oklch, ${color} 14%, transparent)`,
                      }}
                    >
                      {riskLabels[riskLevel] ?? riskLevel}
                    </span>
                  </div>

                  <div className="flex gap-3 text-[10px]">
                    <span style={{ color: 'var(--ink-500)' }}>
                      {cpaMetrics.dcpa_nm.toFixed(2)}
                      <span className="ml-0.5">nm</span>
                    </span>
                    <span
                      className="tnum font-mono"
                      style={{
                        color: cpaMetrics.tcpa_sec < 300 ? 'var(--risk-warning)' : 'var(--ink-500)',
                      }}
                    >
                      {(cpaMetrics.tcpa_sec / 60).toFixed(1)}
                      <span className="ml-0.5">min</span>
                    </span>
                  </div>

                  {(lowConfidence || hasExplanation) && (
                    <div className="mt-1.5 flex items-center gap-2">
                      {hasExplanation && (
                        <span
                          className="flex items-center gap-1 text-[9px] font-medium"
                          style={{ color: 'var(--accent)' }}
                        >
                          <span
                            className="anim-soft-pulse"
                            style={{
                              display: 'inline-block',
                              width: 5,
                              height: 5,
                              borderRadius: 999,
                              background: 'var(--accent)',
                            }}
                          />
                          AI 评估
                        </span>
                      )}
                      {lowConfidence && (
                        <span className="text-[9px]" style={{ color: 'var(--risk-warning)' }}>
                          低置信 {((riskConfidence ?? 0) * 100).toFixed(0)}%
                        </span>
                      )}
                    </div>
                  )}
                </div>
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}
