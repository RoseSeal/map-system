import { useEffect, useRef } from 'react';
import type { AdvisoryPayload, AdvisoryUrgency, RiskLevel } from '../../types/schema';
import { useRiskStore, selectActiveAdvisory } from '../../store';

const riskColors: Record<RiskLevel, string> = {
  SAFE: 'oklch(0.76 0.11 158)',
  CAUTION: 'oklch(0.82 0.12 85)',
  WARNING: 'oklch(0.72 0.15 55)',
  ALARM: 'oklch(0.66 0.18 22)',
};

const urgencyLabels: Record<AdvisoryUrgency, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  IMMEDIATE: '立即',
};

const urgencyColors: Record<AdvisoryUrgency, string> = {
  LOW: 'var(--risk-safe)',
  MEDIUM: 'var(--risk-caution)',
  HIGH: 'var(--risk-warning)',
  IMMEDIATE: 'var(--risk-alarm)',
};

const actionTypeLabels: Record<string, string> = {
  COURSE_CHANGE: '改变航向',
  SPEED_CHANGE: '改变航速',
  MAINTAIN_COURSE: '保持航向',
  MONITOR: '持续监控',
  UNKNOWN: '未知',
};

interface AdvisoryCardProps {
  advisory: AdvisoryPayload;
  isDarkMode: boolean;
}

function AdvisoryCardContent({ advisory, isDarkMode }: AdvisoryCardProps) {
  const color = riskColors[advisory.risk_level] ?? riskColors.WARNING;
  const urgency = advisory.recommended_action.urgency;
  const urgencyColor = urgencyColors[urgency] ?? urgencyColors.MEDIUM;
  const actionLabel = actionTypeLabels[advisory.recommended_action.type] ?? advisory.recommended_action.type;

  return (
    <div
      className="overflow-hidden rounded-2xl"
      style={{
        background: isDarkMode ? 'rgba(255,255,255,0.04)' : 'rgba(255,255,255,0.6)',
        border: `0.5px solid color-mix(in oklch, ${color} 30%, transparent)`,
        boxShadow: `0 4px 16px -6px color-mix(in oklch, ${color} 20%, transparent)`,
      }}
    >
      <div
        className="flex items-center justify-between px-4 py-2.5"
        style={{ borderBottom: `0.5px solid color-mix(in oklch, ${color} 18%, transparent)` }}
      >
        <div className="flex items-center gap-2">
          <span
            className="rounded-full px-1.5 py-0.5 text-[9px] font-semibold"
            style={{
              background: `color-mix(in oklch, ${color} 15%, transparent)`,
              color,
            }}
          >
            {advisory.risk_level}
          </span>
          <span className="text-[11px] font-semibold" style={{ color: 'var(--ink-900)' }}>
            场景建议
          </span>
        </div>
        <div className="flex items-center gap-1.5">
          <span
            className="rounded-full px-1.5 py-0.5 text-[9px] font-semibold"
            style={{ background: `color-mix(in oklch, ${urgencyColor} 15%, transparent)`, color: urgencyColor }}
          >
            {urgencyLabels[urgency]}
          </span>
          <span className="text-[9px]" style={{ color: 'var(--ink-500)' }}>
            {actionLabel}
          </span>
        </div>
      </div>

      <div className="px-4 py-3 space-y-2">
        <p className="text-[12.5px] leading-relaxed" style={{ color: 'var(--ink-700)' }}>
          {advisory.summary}
        </p>

        <p className="text-[11px] font-medium" style={{ color: 'var(--ink-700)' }}>
          {advisory.recommended_action.description}
        </p>

        {advisory.evidence_items.length > 0 && (
          <ul className="space-y-0.5">
            {advisory.evidence_items.map((item, idx) => (
              <li
                key={idx}
                className="text-[10px] leading-relaxed"
                style={{ color: 'var(--ink-500)' }}
              >
                · {item}
              </li>
            ))}
          </ul>
        )}

        <div className="pt-1 flex items-center justify-between">
          <span className="text-[9px]" style={{ color: 'var(--ink-500)' }}>
            {advisory.provider} · {advisory.timestamp}
          </span>
          <span className="text-[9px]" style={{ color: 'var(--ink-500)' }}>
            有效至 {advisory.valid_until}
          </span>
        </div>
      </div>
    </div>
  );
}

export function AdvisoryCard({ isDarkMode }: { isDarkMode: boolean }) {
  const advisory = useRiskStore(selectActiveAdvisory);
  const expireActiveAdvisory = useRiskStore((state) => state.expireActiveAdvisory);
  const expireTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (expireTimerRef.current !== null) {
      clearTimeout(expireTimerRef.current);
      expireTimerRef.current = null;
    }

    if (!advisory) {
      return;
    }

    const validUntilMs = Date.parse(advisory.valid_until);
    if (Number.isNaN(validUntilMs)) {
      return;
    }

    const remaining = validUntilMs - Date.now();
    if (remaining <= 0) {
      expireActiveAdvisory(advisory.advisory_id);
      return;
    }

    expireTimerRef.current = setTimeout(() => {
      expireActiveAdvisory(advisory.advisory_id);
    }, remaining);

    return () => {
      if (expireTimerRef.current !== null) {
        clearTimeout(expireTimerRef.current);
        expireTimerRef.current = null;
      }
    };
  }, [advisory, expireActiveAdvisory]);

  if (!advisory) {
    return null;
  }

  return <AdvisoryCardContent advisory={advisory} isDarkMode={isDarkMode} />;
}
