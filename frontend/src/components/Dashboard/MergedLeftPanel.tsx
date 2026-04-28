import { useCallback, useEffect, useRef, useState } from 'react';
import {
  selectChatConnectionState,
  selectEnvironment,
  selectExplanationsByTargetId,
  selectGovernance,
  selectIsLowTrust,
  selectOwnShip,
  selectRiskConnectionState,
  selectSelectedTargetIds,
  selectTargets,
  useAiCenterStore,
  useRiskStore,
} from '../../store';
import { useThemeStore } from '../../store/useThemeStore';
import type { DisplayConnectionState } from '../../types/connection';
import { translateEncounterType } from '../../utils/riskDisplay';
import { CpaArc } from './CpaArc';

// ── Constants ─────────────────────────────────────────────────────────────────
const COMPACT_THRESHOLD = 180;
const COMPACT_HEIGHT = 72;    // px: snap target when releasing in compact range
const SNAP_TO_HIDE = 40;
const MIN_TARGETS_HEIGHT = 160;
const DEFAULT_STATUS_HEIGHT = 300;
const PANEL_WIDTH = 288;
const STATUS_PADDING_BOTTOM_FULL = 0;
const STATUS_PADDING_BOTTOM_COMPACT = 2;
const FULL_DIVIDER_HEIGHT = 12;
const COMPACT_DIVIDER_HEIGHT = 5;

// ── Types ─────────────────────────────────────────────────────────────────────
type DetailMode = 'full' | 'mid' | 'compact' | 'hidden';

const RISK_COLORS: Record<string, string> = {
  SAFE:    'oklch(0.76 0.11 158)',
  CAUTION: 'oklch(0.82 0.12 85)',
  WARNING: 'oklch(0.72 0.15 55)',
  ALARM:   'oklch(0.66 0.18 22)',
};
const RISK_LABELS: Record<string, string> = {
  SAFE: '安全', CAUTION: '注意', WARNING: '警告', ALARM: '警报',
};
const LEVEL_SCORE: Record<string, number> = {
  ALARM: 4, WARNING: 3, CAUTION: 2, SAFE: 1,
};

// ── Sub-components ────────────────────────────────────────────────────────────
function ConnectionDot({ label, state }: { label: string; state: DisplayConnectionState }) {
  const color = state === 'connected'
    ? 'var(--risk-safe)'
    : state === 'reconnecting'
      ? 'var(--risk-caution)'
      : 'var(--risk-alarm)';
  return (
    <div className="flex items-center gap-1.5">
      <span className="font-mono text-[9px]" style={{ color: 'var(--ink-500)' }}>{label}</span>
      <span
        className={state === 'reconnecting' ? 'anim-soft-pulse' : ''}
        style={{ display: 'inline-block', width: 6, height: 6, borderRadius: 999, background: color }}
      />
    </div>
  );
}

function toCardinalDirection(deg: number | null): string {
  if (deg == null || Number.isNaN(deg)) return '--';
  const cardinal = ['北', '东北', '东', '东南', '南', '西南', '西', '西北'];
  return cardinal[Math.round(((deg % 360) + 360) % 360 / 45) % 8];
}

function toWeatherCodeCN(code: string | null): string {
  if (!code) return '--';
  const map: Record<string, string> = {
    CLEAR: '晴朗', CLOUDS: '多云', OVERCAST: '阴', RAIN: '雨',
    DRIZZLE: '小雨', SHOWER: '阵雨', THUNDERSTORM: '雷暴',
    SNOW: '雪', SLEET: '雨夹雪', FOG: '雾', MIST: '薄雾', HAZE: '霾', WINDY: '大风',
  };
  return map[code.toUpperCase()] ?? code;
}

// ── Main component ─────────────────────────────────────────────────────────────
export function MergedLeftPanel() {
  // ── Store ──
  const ownShip             = useRiskStore(selectOwnShip);
  const governance          = useRiskStore(selectGovernance);
  const environment         = useRiskStore(selectEnvironment);
  const isLowTrust          = useRiskStore(selectIsLowTrust);
  const riskConnectionState = useRiskStore(selectRiskConnectionState);
  const chatConnectionState = useAiCenterStore(selectChatConnectionState);
  const targets             = useRiskStore(selectTargets);
  const explanationsByTargetId = useRiskStore(selectExplanationsByTargetId);
  const selectedTargetIds   = useRiskStore(selectSelectedTargetIds);
  const selectTarget        = useRiskStore((s) => s.selectTarget);
  const requestAiCenterOpen = useAiCenterStore((s) => s.requestAiCenterOpen);
  const { isDarkMode }      = useThemeStore();

  // ── SOG tween ──
  const currentSog = ownShip?.dynamics.sog ?? 0;
  const [displaySog, setDisplaySog] = useState(currentSog);
  const sogRef = useRef(currentSog);

  useEffect(() => {
    if (!ownShip) { setDisplaySog(0); sogRef.current = 0; return; }
    const from = sogRef.current;
    const to   = currentSog;
    if (from === to) { setDisplaySog(to); sogRef.current = to; return; }
    const start = performance.now();
    const dur   = 500;
    let frameId = 0;
    const tick = (now: number) => {
      const p = Math.min(1, (now - start) / dur);
      setDisplaySog(from + (to - from) * (1 - (1 - p) ** 3));
      if (p < 1) frameId = requestAnimationFrame(tick);
      else sogRef.current = to;
    };
    frameId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frameId);
  }, [currentSog, ownShip]);

  // ── Drag state ──
  const panelRef         = useRef<HTMLDivElement>(null);
  const statusContentRef = useRef<HTMLDivElement>(null);
  const navGridRef       = useRef<HTMLDivElement>(null);
  const modeRef          = useRef<DetailMode>('full');  // mirrors mode, readable inside event closures
  const liveHeight       = useRef(DEFAULT_STATUS_HEIGHT);
  const isDragging       = useRef(false);
  const startY           = useRef(0);
  const startHeight      = useRef(0);

  const [statusHeight, setStatusHeight] = useState(DEFAULT_STATUS_HEIGHT);
  const [mode, setMode]                 = useState<DetailMode>('full');

  function getSnapHeights() {
    const full = statusContentRef.current?.scrollHeight ?? DEFAULT_STATUS_HEIGHT;
    const navBottom = navGridRef.current
      ? navGridRef.current.offsetTop + navGridRef.current.offsetHeight
      : full;
    const middle = Math.max(COMPACT_HEIGHT + 1, Math.min(full, navBottom));
    return { compact: COMPACT_HEIGHT, middle, full };
  }

  function resolveModeFromHeight(h: number, middleH: number, fullH: number): DetailMode {
    if (h === 0) return 'hidden';
    if (h < COMPACT_THRESHOLD) return 'compact';
    return Math.abs(h - middleH) <= Math.abs(h - fullH) ? 'mid' : 'full';
  }

  function applyHeight(h: number) {
    const { middle, full } = getSnapHeights();
    const targetMode = resolveModeFromHeight(h, middle, full);
    const dividerHeight = targetMode === 'compact' ? COMPACT_DIVIDER_HEIGHT : FULL_DIVIDER_HEIGHT;
    const panelMax = panelRef.current
      ? panelRef.current.offsetHeight - MIN_TARGETS_HEIGHT - dividerHeight
      : Infinity;
    // In full/mid mode, also cap by actual content height to prevent over-dragging past content
    const contentMax = (targetMode === 'full' || targetMode === 'mid') && statusContentRef.current
      ? statusContentRef.current.scrollHeight
      : Infinity;
    const clamped = Math.max(0, Math.min(panelMax, Math.min(contentMax, h)));
    const finalMode = resolveModeFromHeight(clamped, middle, full);
    modeRef.current = finalMode;
    liveHeight.current = clamped;
    setStatusHeight(clamped);
    setMode(finalMode);
    localStorage.setItem('mergedPanel_statusHeight', String(clamped));
  }

  // Restore from localStorage on mount
  useEffect(() => {
    const saved = localStorage.getItem('mergedPanel_statusHeight');
    if (saved) applyHeight(Number(saved));
  }, []);

  const onMouseDown = useCallback((e: React.MouseEvent) => {
    isDragging.current  = true;
    startY.current      = e.clientY;
    startHeight.current = liveHeight.current;
    document.body.style.cursor     = 'ns-resize';
    document.body.style.userSelect = 'none';
    e.preventDefault();
  }, []);

  const onTouchStart = useCallback((e: React.TouchEvent) => {
    isDragging.current  = true;
    startY.current      = e.touches[0].clientY;
    startHeight.current = liveHeight.current;
  }, []);

  useEffect(() => {
    function getMaxH() {
      const dividerHeight = modeRef.current === 'compact' ? COMPACT_DIVIDER_HEIGHT : FULL_DIVIDER_HEIGHT;
      const panelMax = panelRef.current
        ? panelRef.current.offsetHeight - MIN_TARGETS_HEIGHT - dividerHeight
        : 9999;
      // Cap by content scrollHeight while in full/mid mode.
      if ((modeRef.current === 'full' || modeRef.current === 'mid') && statusContentRef.current) {
        return Math.min(panelMax, statusContentRef.current.scrollHeight);
      }
      return panelMax;
    }
    function liveUpdate(clientY: number) {
      const next = Math.max(0, Math.min(getMaxH(), startHeight.current + (clientY - startY.current)));
      liveHeight.current = next;
      setStatusHeight(next);
      // Never switch to compact mid-drag: the full content clips smoothly via overflow:hidden.
      // Switch to full immediately when expanding past threshold so content appears.
      // Switch to hidden only when truly collapsed (< SNAP_TO_HIDE).
      if (next < SNAP_TO_HIDE) {
        modeRef.current = 'hidden';
        setMode('hidden');
      } else if (next >= COMPACT_THRESHOLD) {
        modeRef.current = 'full';
        setMode('full');
      }
      // SNAP_TO_HIDE ≤ next < COMPACT_THRESHOLD: keep current mode (no mid-drag blank-space jump)
    }
    function snapCommit() {
      const h = liveHeight.current;
      if (h < SNAP_TO_HIDE) {
        applyHeight(0);
        return;
      }
      // Nearest-neighbor snap across compact / middle / full.
      const { compact, middle, full } = getSnapHeights();
      const toCompact = Math.abs(h - compact);
      const toMiddle  = Math.abs(h - middle);
      const toFull    = Math.abs(h - full);
      if (toCompact <= toMiddle && toCompact <= toFull) {
        applyHeight(compact);
      } else if (toMiddle <= toFull) {
        applyHeight(middle);
      } else {
        applyHeight(full);
      }
    }
    function onMouseMove(e: MouseEvent) {
      if (!isDragging.current) return;
      liveUpdate(e.clientY);
    }
    function onMouseUp() {
      if (!isDragging.current) return;
      isDragging.current = false;
      document.body.style.cursor     = '';
      document.body.style.userSelect = '';
      snapCommit();
    }
    function onTouchMove(e: TouchEvent) {
      if (!isDragging.current) return;
      liveUpdate(e.touches[0].clientY);
    }
    function onTouchEnd() {
      if (!isDragging.current) return;
      isDragging.current = false;
      snapCommit();
    }
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
    window.addEventListener('touchmove', onTouchMove);
    window.addEventListener('touchend', onTouchEnd);
    return () => {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
      window.removeEventListener('touchmove', onTouchMove);
      window.removeEventListener('touchend', onTouchEnd);
    };
  }, []);

  // ── Derived ──
  const glassClass = isDarkMode ? 'glass-vision-dark' : 'glass-vision';
  const weather     = environment?.weather ?? null;
  const hasWeather  = weather != null;

  const healthLabel = ownShip?.platform_health.status === 'NORMAL'   ? '正常'
    : ownShip?.platform_health.status === 'DEGRADED' ? '降级'
    : ownShip?.platform_health.status === 'NUC'      ? 'NUC'
    : (ownShip?.platform_health.status ?? '—');

  const batteryPct = typeof (ownShip?.platform_health as Record<string, unknown> | undefined)?.battery_pct === 'number'
    ? (ownShip!.platform_health as unknown as { battery_pct: number }).battery_pct
    : null;

  const activeAlertCount = environment?.active_alerts.length ?? 0;
  const isDetailed = mode === 'full' || mode === 'mid';
  const isCompact = mode === 'compact';
  const isMiddleSnapped = mode === 'mid';
  const statusPaddingBottom = mode === 'compact'
    ? STATUS_PADDING_BOTTOM_COMPACT
    : STATUS_PADDING_BOTTOM_FULL;

  const sortedTargets = [...targets].sort((a, b) => {
    const d = (LEVEL_SCORE[b.risk_assessment.risk_level] ?? 0)
            - (LEVEL_SCORE[a.risk_assessment.risk_level] ?? 0);
    return d !== 0 ? d : (b.risk_assessment.risk_score ?? 0) - (a.risk_assessment.risk_score ?? 0);
  });
  const counts = targets.reduce<Record<string, number>>((acc, t) => {
    const lvl = t.risk_assessment.risk_level;
    acc[lvl] = (acc[lvl] ?? 0) + 1;
    return acc;
  }, {});

  // ── Render ──
  return (
    <div
      ref={panelRef}
      className={`${glassClass} anim-rise`}
      style={{
        position: 'absolute',
        top: 16, bottom: 16, left: 16,
        width: PANEL_WIDTH,
        borderRadius: 22,
        zIndex: 10,
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
        pointerEvents: 'auto',
      }}
    >
      {/* ── Restore button (hidden mode) ── */}
      {mode === 'hidden' && (
        <button
          onClick={() => applyHeight(DEFAULT_STATUS_HEIGHT)}
          style={{
            position: 'absolute',
            top: 8, left: '50%',
            transform: 'translateX(-50%)',
            padding: '4px 14px',
            borderRadius: 999,
            background: 'var(--accent)',
            color: '#fff',
            fontSize: 10,
            fontWeight: 600,
            border: 'none',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: 5,
            boxShadow: '0 2px 8px -2px rgba(0,0,0,0.2)',
            zIndex: 12,
            whiteSpace: 'nowrap',
          }}
        >
          ↑ 显示本船态势
        </button>
      )}

      {/* ══ Status section ══ */}
      <div style={{ height: mode === 'hidden' ? 0 : statusHeight, flexShrink: 0, overflow: 'hidden' }}>
        <div ref={statusContentRef} style={{ padding: `16px 16px ${statusPaddingBottom}px` }}>

          {/* Loading state */}
          {(!ownShip || !governance) && mode !== 'hidden' && (
            <>
              <div className="mb-3 flex items-center justify-between">
                <span className="text-[11px] font-semibold" style={{ color: 'var(--ink-700)' }}>本船态势</span>
                <div className="flex items-center gap-3">
                  <ConnectionDot label="STREAM" state={riskConnectionState} />
                  <ConnectionDot label="AI-WS"  state={chatConnectionState} />
                </div>
              </div>
              <div className="anim-soft-pulse py-6 text-center font-mono text-[10px]" style={{ color: 'var(--ink-500)' }}>
                INITIALIZING TELEMETRY...
              </div>
            </>
          )}

          {ownShip && governance && (
            <>
              {/* Header row */}
              <div className={`${isDetailed ? 'mb-3' : 'mb-2'} flex items-center justify-between`}>
                <div className="flex items-center gap-2">
                  <span className="relative flex-shrink-0" style={{ width: 8, height: 8 }}>
                    <span className="absolute inset-0 rounded-full animate-ping" style={{ background: 'var(--risk-safe)', opacity: 0.5 }} />
                    <span className="absolute inset-0 rounded-full" style={{ background: 'var(--risk-safe)' }} />
                  </span>
                  <span className={`${isCompact ? 'text-[10px]' : 'text-[11px]'} font-semibold`} style={{ color: 'var(--ink-700)' }}>
                    本船态势
                  </span>
                </div>
                <div className={`flex items-center ${isCompact ? 'gap-2' : 'gap-3'}`}>
                  <ConnectionDot label={isCompact ? 'SYSTEM' : 'STREAM'} state={riskConnectionState} />
                  <ConnectionDot label="AI-WS"  state={chatConnectionState} />
                </div>
              </div>

              {/* Trust warning (full only) */}
              {isDetailed && isLowTrust && (
                <div
                  className="anim-soft-pulse mb-3 flex items-center gap-2 rounded-xl px-3 py-1.5"
                  style={{
                    background: 'color-mix(in oklch, var(--risk-warning) 10%, transparent)',
                    border: '0.5px solid color-mix(in oklch, var(--risk-warning) 25%, transparent)',
                  }}
                >
                  <span style={{ width: 6, height: 6, borderRadius: 999, background: 'var(--risk-warning)', display: 'inline-block' }} />
                  <span className="text-[10px] font-semibold" style={{ color: 'var(--risk-warning)' }}>
                    Trust Warning · {(governance.trust_factor * 100).toFixed(0)}%
                  </span>
                </div>
              )}

              {/* SOG hero + weather/COG row */}
              <div className={`flex items-start gap-3 ${isDetailed ? 'mb-3' : ''}`}>
                {/* SOG */}
                <div className="flex-shrink-0" style={{ marginTop: mode === 'compact' ? 3 : 0 }}>
                  <div className="mb-0.5 flex items-baseline gap-2">
                    <span
                      className="tnum font-semibold leading-none tracking-tight"
                      style={{ fontSize: mode === 'compact' ? 24 : 38, color: 'var(--ink-900)' }}
                    >
                      {displaySog.toFixed(1)}
                    </span>
                    <div className="flex flex-col gap-1">
                      <span className="text-[13px] font-medium" style={{ color: 'var(--ink-500)' }}>kn</span>
                      {isDetailed && (
                        <span
                          className="rounded-full px-1.5 py-0.5 text-[9px] font-medium"
                          style={{
                            background: 'color-mix(in oklch, var(--risk-safe) 14%, transparent)',
                            color: 'var(--risk-safe)',
                          }}
                        >
                          {healthLabel}
                        </span>
                      )}
                    </div>
                  </div>
                  {isDetailed && (
                    <span className="text-[10px]" style={{ color: 'var(--ink-500)' }}>SOG · 对地航速</span>
                  )}
                </div>

                {/* Full: vertical divider + weather grid */}
                {isDetailed && (
                  <>
                    <div
                      className="self-stretch"
                      style={{ width: '0.5px', background: 'color-mix(in oklch, var(--ink-500) 12%, transparent)', flexShrink: 0 }}
                    />
                    <div className="grid min-w-0 flex-1 grid-cols-2 gap-x-3 gap-y-1.5 pt-0.5">
                      {[
                        { label: '天气',  value: hasWeather ? toWeatherCodeCN(weather!.weather_code) : '--' },
                        { label: '能见度', value: weather?.visibility_nm != null ? `${weather.visibility_nm.toFixed(1)} nm` : '--' },
                        { label: '风向',  value: toCardinalDirection(weather?.wind.direction_from_deg ?? null) },
                        { label: '风速',  value: weather?.wind.speed_kn != null ? `${Math.round(weather.wind.speed_kn)} kn` : '--' },
                      ].map(({ label, value }) => (
                        <div key={label}>
                          <div className="mb-0.5 text-[9px] tracking-wide" style={{ color: 'var(--ink-500)' }}>{label}</div>
                          <div
                            className="tnum font-mono font-semibold text-[11px]"
                            style={{ color: hasWeather ? 'var(--ink-900)' : 'var(--ink-300)' }}
                          >
                            {value}
                          </div>
                        </div>
                      ))}
                      {activeAlertCount > 0 && (
                        <div className="col-span-2 mt-0.5 text-[9px] font-medium" style={{ color: 'var(--risk-warning)' }}>
                          环境告警 {activeAlertCount} 项
                        </div>
                      )}
                    </div>
                  </>
                )}

                {/* Compact: COG + HDG inline */}
                {mode === 'compact' && (
                  <div
                    className="flex flex-1 items-center justify-end gap-4"
                    style={{ marginTop: -3 }}
                  >
                    {[
                      { label: 'COG', value: `${ownShip.dynamics.cog.toFixed(1)}°` },
                      { label: 'HDG', value: `${ownShip.dynamics.hdg.toFixed(1)}°` },
                    ].map(({ label, value }) => (
                      <div key={label}>
                        <div className="text-[9px] tracking-wide" style={{ color: 'var(--ink-500)' }}>{label}</div>
                        <div className="tnum font-mono font-semibold text-[13px]" style={{ color: 'var(--ink-900)' }}>{value}</div>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Navigation grid + battery (full only) */}
              {isDetailed && (
                <>
                  <div
                    ref={navGridRef}
                    className="grid grid-cols-2 gap-x-4 gap-y-2 pb-3"
                    style={{
                      borderBottom: isMiddleSnapped
                        ? 'none'
                        : '0.5px solid color-mix(in oklch, var(--ink-500) 12%, transparent)',
                    }}
                  >
                    {[
                      { label: 'COG 航向', value: `${ownShip.dynamics.cog.toFixed(1)}°` },
                      { label: 'HDG 艏向', value: `${ownShip.dynamics.hdg.toFixed(1)}°` },
                      { label: '经度',     value: `E ${ownShip.position.lon.toFixed(4)}°`, small: true },
                      { label: '纬度',     value: `N ${ownShip.position.lat.toFixed(4)}°`, small: true },
                    ].map(({ label, value, small }) => (
                      <div key={label}>
                        <div className="mb-0.5 text-[9px] tracking-wide" style={{ color: 'var(--ink-500)' }}>{label}</div>
                        <div
                          className={`tnum font-mono font-semibold ${small ? 'text-[11px]' : 'text-[15px]'}`}
                          style={{ color: 'var(--ink-900)' }}
                        >
                          {value}
                        </div>
                      </div>
                    ))}
                  </div>

                  <div
                    className="py-2"
                  >
                    <div className="mb-1.5 flex items-center justify-between">
                      <span className="text-[10px]" style={{ color: 'var(--ink-500)' }}>电力推进 · 电池</span>
                      <span className="tnum text-[11px] font-semibold" style={{ color: 'var(--ink-900)' }}>
                        {batteryPct != null ? `${batteryPct}%` : '—'}
                      </span>
                    </div>
                    {batteryPct != null && (
                      <div
                        className="h-1 overflow-hidden rounded-full"
                        style={{ background: 'color-mix(in oklch, var(--ink-500) 10%, transparent)' }}
                      >
                        <div
                          className="h-full rounded-full transition-all duration-700"
                          style={{
                            width: `${batteryPct}%`,
                            background: 'linear-gradient(90deg, oklch(0.76 0.11 158), oklch(0.70 0.13 175))',
                          }}
                        />
                      </div>
                    )}
                    <div className="mt-2 flex items-center justify-between text-[10px]">
                      <span style={{ color: 'var(--ink-500)' }}>信任因子</span>
                      <span className="tnum font-mono font-medium" style={{ color: 'var(--ink-700)' }}>
                        {(governance.trust_factor * 100).toFixed(0)}%
                      </span>
                    </div>
                  </div>
                </>
              )}
            </>
          )}
        </div>
      </div>

      {/* ══ Draggable divider ══ */}
      <div
        onMouseDown={onMouseDown}
        onTouchStart={onTouchStart}
        style={{
          height: mode === 'compact' ? COMPACT_DIVIDER_HEIGHT : FULL_DIVIDER_HEIGHT,
          flexShrink: 0,
          cursor: 'ns-resize',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          position: 'relative',
        }}
      >
        <div style={{
          position: 'absolute',
          left: '15%', right: '15%',
          height: '0.5px',
          background: 'color-mix(in oklch, var(--ink-500) 14%, transparent)',
        }} />
        <div
          className="divider-grip"
          style={{
            position: 'relative',
            width: 28,
            height: 4,
            borderRadius: 2,
            background: 'color-mix(in oklch, var(--ink-500) 28%, transparent)',
          }}
        />
      </div>

      {/* ══ Targets section ══ */}
      <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        {/* Header */}
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
              <span className="text-[13px] font-semibold" style={{ color: 'var(--ink-700)' }}>周边目标</span>
            </div>
            <span
              className="tnum rounded-full px-2 py-0.5 font-mono text-[10px]"
              style={{ background: 'color-mix(in oklch, var(--ink-500) 10%, transparent)', color: 'var(--ink-700)' }}
            >
              {targets.length} 已追踪
            </span>
          </div>

          {/* Risk distribution bar */}
          <div
            className="flex h-1 overflow-hidden rounded-full"
            style={{ background: 'color-mix(in oklch, var(--ink-500) 10%, transparent)' }}
          >
            {(['SAFE', 'CAUTION', 'WARNING', 'ALARM'] as const).map((lvl) =>
              counts[lvl] ? (
                <div
                  key={lvl}
                  className="h-full transition-all duration-500"
                  style={{ flex: counts[lvl], background: RISK_COLORS[lvl] }}
                />
              ) : null
            )}
          </div>
        </div>

        {/* Target list */}
        <div className="scrollbar-apple flex-1 min-h-0 space-y-2 overflow-y-auto px-3 pb-3">
          {sortedTargets.length === 0 ? (
            <div className="flex h-full items-center justify-center text-[11px]" style={{ color: 'var(--ink-500)' }}>
              无周边目标
            </div>
          ) : sortedTargets.map((target) => {
            const { cpa_metrics, encounter_type, risk_confidence, risk_level } = target.risk_assessment;
            const isSelected     = selectedTargetIds.includes(target.id);
            const hasExplanation = Boolean(explanationsByTargetId[target.id]);
            const lowConfidence  = risk_confidence !== undefined && risk_confidence < 0.5;
            const color          = RISK_COLORS[risk_level] ?? RISK_COLORS.SAFE;
            const encounterLabel = translateEncounterType(encounter_type);

            return (
              <button
                key={target.id}
                type="button"
                onClick={() => {
                  selectTarget(target.id);
                  if (hasExplanation) requestAiCenterOpen();
                }}
                className="group w-full text-left transition-all duration-300 ease-out"
                style={{
                  padding: '7px 10px',
                  borderRadius: 14,
                  background: isSelected
                    ? `color-mix(in oklch, ${color} 9%, ${isDarkMode ? 'rgba(15,23,42,0.7)' : 'rgba(255,255,255,0.92)'})`
                    : isDarkMode ? 'rgba(255,255,255,0.03)' : 'rgba(255,255,255,0.5)',
                  border: `0.5px solid ${isSelected
                    ? `color-mix(in oklch, ${color} 40%, transparent)`
                    : isDarkMode ? 'rgba(255,255,255,0.05)' : 'rgba(15,23,42,0.05)'}`,
                  boxShadow: isSelected
                    ? `0 3px 12px -4px color-mix(in oklch, ${color} 28%, transparent)`
                    : 'none',
                  transform: isSelected ? 'translateY(-1px)' : '',
                }}
              >
                <div className="flex items-start gap-3">
                  <CpaArc
                    tcpa_sec={cpa_metrics.tcpa_sec}
                    dcpa_nm={cpa_metrics.dcpa_nm}
                    riskLevel={risk_level}
                    size={36}
                  />
                  <div className="min-w-0 flex-1">
                    <div className="mb-1.5 flex items-center justify-between gap-2">
                      <div className="flex min-w-0 items-center gap-1.5">
                        <span className="tnum truncate font-mono text-[12px] font-semibold" style={{ color: 'var(--ink-900)' }}>
                          {target.id}
                        </span>
                        {risk_level !== 'SAFE' && encounterLabel && (
                          <span
                            className="shrink-0 rounded px-1.5 py-0.5 text-[8px] font-medium"
                            style={{ background: 'color-mix(in oklch, var(--ink-500) 10%, transparent)', color: 'var(--ink-500)' }}
                          >
                            {encounterLabel}
                          </span>
                        )}
                      </div>
                      <span
                        className="shrink-0 rounded-full px-2 py-0.5 text-[9px] font-semibold tracking-wide"
                        style={{ color, background: `color-mix(in oklch, ${color} 14%, transparent)` }}
                      >
                        {RISK_LABELS[risk_level] ?? risk_level}
                      </span>
                    </div>

                    <div className="flex gap-3 text-[10px]">
                      <span style={{ color: 'var(--ink-500)' }}>
                        {cpa_metrics.dcpa_nm.toFixed(2)}<span className="ml-0.5">nm</span>
                      </span>
                      <span
                        className="tnum font-mono"
                        style={{ color: cpa_metrics.tcpa_sec < 300 ? 'var(--risk-warning)' : 'var(--ink-500)' }}
                      >
                        {(cpa_metrics.tcpa_sec / 60).toFixed(1)}<span className="ml-0.5">min</span>
                      </span>
                    </div>

                    {(lowConfidence || hasExplanation) && (
                      <div className="mt-1.5 flex items-center gap-2">
                        {hasExplanation && (
                          <span className="flex items-center gap-1 text-[9px] font-medium" style={{ color: 'var(--accent)' }}>
                            <span
                              className="anim-soft-pulse"
                              style={{ display: 'inline-block', width: 5, height: 5, borderRadius: 999, background: 'var(--accent)' }}
                            />
                            AI 评估
                          </span>
                        )}
                        {lowConfidence && (
                          <span className="text-[9px]" style={{ color: 'var(--risk-warning)' }}>
                            低置信 {((risk_confidence ?? 0) * 100).toFixed(0)}%
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
    </div>
  );
}
