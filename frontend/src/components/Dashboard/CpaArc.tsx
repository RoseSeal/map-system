import type { RiskLevel } from '../../types/schema';

interface CpaArcProps {
  tcpa_sec: number;
  dcpa_nm: number;
  riskLevel: RiskLevel;
  size?: number;
  maxTcpa?: number;
  maxDcpa?: number;
}

const riskColors: Record<RiskLevel, string> = {
  SAFE: 'oklch(0.76 0.11 158)',
  CAUTION: 'oklch(0.82 0.12 85)',
  WARNING: 'oklch(0.72 0.15 55)',
  ALARM: 'oklch(0.66 0.18 22)',
};

export function CpaArc({
  tcpa_sec: tcpaSec,
  dcpa_nm: dcpaNm,
  riskLevel,
  size = 44,
  maxTcpa = 1800,
  maxDcpa = 3,
}: CpaArcProps) {
  const radius = 15;
  const circumference = 2 * Math.PI * radius;
  const arcRatio = Math.min(1, Math.max(0, tcpaSec / maxTcpa));
  const arcLength = circumference * arcRatio;
  const gapLength = circumference - arcLength;

  const dcpaRatio = Math.min(1, Math.max(0, dcpaNm / maxDcpa));
  const dotRadius = 2.5 + (dcpaRatio * 6.5);
  const color = riskColors[riskLevel];
  const tcpaLabel = tcpaSec < 60 ? `${Math.round(tcpaSec)}s` : `${(tcpaSec / 60).toFixed(1)}m`;

  return (
    <div className="flex shrink-0 select-none flex-col items-center gap-0.5">
      <svg
        width={size}
        height={size}
        viewBox="0 0 36 36"
        aria-label={`TCPA ${tcpaLabel}, DCPA ${dcpaNm.toFixed(2)} nm`}
      >
        <circle
          cx="18"
          cy="18"
          r={radius}
          fill="none"
          stroke="color-mix(in oklch, var(--ink-500) 12%, transparent)"
          strokeWidth="2.5"
        />
        <circle
          cx="18"
          cy="18"
          r={radius}
          fill="none"
          stroke={color}
          strokeWidth="2.5"
          strokeDasharray={`${arcLength} ${gapLength}`}
          strokeLinecap="round"
          transform="rotate(-90 18 18)"
          opacity="0.85"
        />
        <circle
          cx="18"
          cy="18"
          r={dotRadius}
          fill={color}
          opacity="0.65"
        />
      </svg>
      <span className="tnum font-mono text-[8px] leading-none" style={{ color: 'var(--ink-500)' }}>
        {tcpaLabel}
      </span>
    </div>
  );
}
