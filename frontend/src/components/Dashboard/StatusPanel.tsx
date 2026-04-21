import { useEffect, useRef, useState } from 'react';
import {
  selectChatConnectionState,
  selectEnvironment,
  selectGovernance,
  selectIsLowTrust,
  selectOwnShip,
  selectRiskConnectionState,
  useAiCenterStore,
  useRiskStore,
} from '../../store';
import { useThemeStore } from '../../store/useThemeStore';
import type { DisplayConnectionState } from '../../types/connection';

function ConnectionDot({ label, state }: { label: string; state: DisplayConnectionState }) {
  const color = state === 'connected'
    ? 'var(--risk-safe)'
    : state === 'reconnecting'
      ? 'var(--risk-caution)'
      : 'var(--risk-alarm)';

  return (
    <div className="flex items-center gap-1.5">
      <span className="font-mono text-[9px]" style={{ color: 'var(--ink-500)' }}>
        {label}
      </span>
      <span
        className={state === 'reconnecting' ? 'anim-soft-pulse' : ''}
        style={{
          display: 'inline-block',
          width: '6px',
          height: '6px',
          borderRadius: '999px',
          background: color,
        }}
      />
    </div>
  );
}




function toCardinalDirection(directionFromDeg: number | null): string {
  if (directionFromDeg == null || Number.isNaN(directionFromDeg)) {
    return '--';
  }

  const cardinal = ['北', '东北', '东', '东南', '南', '西南', '西', '西北'];
  const normalized = ((directionFromDeg % 360) + 360) % 360;
  return cardinal[Math.round(normalized / 45) % cardinal.length];
}

function toWeatherCodeCN(code: string | null): string {
  if (!code) return '--';
  const map: Record<string, string> = {
    CLEAR: '晴朗',
    CLOUDS: '多云',
    OVERCAST: '阴',
    RAIN: '雨',
    DRIZZLE: '小雨',
    SHOWER: '阵雨',
    THUNDERSTORM: '雷暴',
    SNOW: '雪',
    SLEET: '雨夹雪',
    FOG: '雾',
    MIST: '薄雾',
    HAZE: '霾',
    WINDY: '大风',
  };
  return map[code.toUpperCase()] ?? code;
}

export function StatusPanel() {
  const ownShip = useRiskStore(selectOwnShip);
  const governance = useRiskStore(selectGovernance);
  const environment = useRiskStore(selectEnvironment);
  const riskConnectionState = useRiskStore(selectRiskConnectionState);
  const chatConnectionState = useAiCenterStore(selectChatConnectionState);
  const isLowTrust = useRiskStore(selectIsLowTrust);
  const { isDarkMode } = useThemeStore();
  const currentSog = ownShip?.dynamics.sog ?? 0;

  const [displaySog, setDisplaySog] = useState(currentSog);
  const sogRef = useRef(currentSog);

  useEffect(() => {
    if (!ownShip) {
      setDisplaySog(0);
      sogRef.current = 0;
      return;
    }

    const from = sogRef.current;
    const to = currentSog;

    if (from === to) {
      setDisplaySog(to);
      sogRef.current = to;
      return;
    }

    const start = performance.now();
    const duration = 500;
    let frameId = 0;

    const tick = (now: number) => {
      const progress = Math.min(1, (now - start) / duration);
      const eased = 1 - ((1 - progress) ** 3);
      setDisplaySog(from + ((to - from) * eased));

      if (progress < 1) {
        frameId = requestAnimationFrame(tick);
      } else {
        sogRef.current = to;
      }
    };

    frameId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frameId);
  }, [currentSog, ownShip]);

  const glassClass = isDarkMode ? 'glass-vision-dark' : 'glass-vision';

  if (!ownShip || !governance) {
    return (
      <div
        className={`${glassClass} anim-rise w-full shrink-0 rounded-[22px] p-4`}
        style={{ maxHeight: '46%' }}
      >
        <div className="mb-3 flex items-center justify-between">
          <span className="text-[11px] font-semibold" style={{ color: 'var(--ink-700)' }}>
            本船态势
          </span>
          <div className="flex items-center gap-3">
            <ConnectionDot label="STREAM" state={riskConnectionState} />
            <ConnectionDot label="AI-WS" state={chatConnectionState} />
          </div>
        </div>
        <div
          className="anim-soft-pulse py-6 text-center font-mono text-[10px]"
          style={{ color: 'var(--ink-500)' }}
        >
          INITIALIZING TELEMETRY...
        </div>
      </div>
    );
  }

  const healthLabel = ownShip.platform_health.status === 'NORMAL'
    ? '正常'
    : ownShip.platform_health.status === 'DEGRADED'
      ? '降级'
      : ownShip.platform_health.status === 'NUC'
        ? 'NUC'
        : ownShip.platform_health.status;

  const batteryPctValue = (ownShip.platform_health as unknown as Record<string, unknown>).battery_pct;
  const batteryPct = typeof batteryPctValue === 'number' ? batteryPctValue : null;

  const weather = environment?.weather ?? null;
  const hasWeather = weather != null;
  const weatherCodeCN = hasWeather ? toWeatherCodeCN(weather.weather_code) : '--';
  const windDirCN = hasWeather ? toCardinalDirection(weather.wind.direction_from_deg) : '--';
  const windSpeedStr = weather?.wind.speed_kn != null ? `${Math.round(weather.wind.speed_kn)} kn` : '--';
  const visibilityStr = weather?.visibility_nm != null ? `${weather.visibility_nm.toFixed(1)} nm` : '--';
  const activeAlertCount = environment?.active_alerts.length ?? 0;

  return (
    <div
      className={`${glassClass} anim-rise w-full shrink-0 overflow-hidden rounded-[22px] p-4`}
      style={{ maxHeight: '46%' }}
    >
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="relative flex-shrink-0" style={{ width: 8, height: 8 }}>
            <span
              className="absolute inset-0 rounded-full animate-ping"
              style={{ background: 'var(--risk-safe)', opacity: 0.5 }}
            />
            <span className="absolute inset-0 rounded-full" style={{ background: 'var(--risk-safe)' }} />
          </span>
          <span className="text-[11px] font-semibold" style={{ color: 'var(--ink-700)' }}>
            本船态势
          </span>
        </div>
        <div className="flex items-center gap-3">
          <ConnectionDot label="STREAM" state={riskConnectionState} />
          <ConnectionDot label="AI-WS" state={chatConnectionState} />
        </div>
      </div>

      {isLowTrust && (
        <div
          className="anim-soft-pulse mb-3 flex items-center gap-2 rounded-xl px-3 py-1.5"
          style={{
            background: 'color-mix(in oklch, var(--risk-warning) 10%, transparent)',
            border: '0.5px solid color-mix(in oklch, var(--risk-warning) 25%, transparent)',
          }}
        >
          <span
            style={{
              width: 6,
              height: 6,
              borderRadius: 999,
              background: 'var(--risk-warning)',
              display: 'inline-block',
            }}
          />
          <span className="text-[10px] font-semibold" style={{ color: 'var(--risk-warning)' }}>
            Trust Warning · {(governance.trust_factor * 100).toFixed(0)}%
          </span>
        </div>
      )}

      {/* SOG + weather row */}
      <div className="mb-3 flex items-start gap-3">
        {/* Left: SOG hero */}
        <div className="flex-shrink-0">
          <div className="mb-0.5 flex items-baseline gap-2">
            <span
              className="tnum font-semibold leading-none tracking-tight"
              style={{ fontSize: 38, color: 'var(--ink-900)' }}
            >
              {displaySog.toFixed(1)}
            </span>
            <div className="flex flex-col gap-1">
              <span className="text-[13px] font-medium" style={{ color: 'var(--ink-500)' }}>kn</span>
              <span
                className="rounded-full px-1.5 py-0.5 text-[9px] font-medium"
                style={{
                  background: 'color-mix(in oklch, var(--risk-safe) 14%, transparent)',
                  color: 'var(--risk-safe)',
                }}
              >
                {healthLabel}
              </span>
            </div>
          </div>
          <span className="text-[10px]" style={{ color: 'var(--ink-500)' }}>SOG · 对地航速</span>
        </div>

        {/* Vertical divider */}
        <div
          className="self-stretch"
          style={{
            width: '0.5px',
            background: 'color-mix(in oklch, var(--ink-500) 12%, transparent)',
            flexShrink: 0,
          }}
        />

        {/* Right: weather mini-grid (same label+value language as main grid) */}
        <div className="grid min-w-0 flex-1 grid-cols-2 gap-x-3 gap-y-1.5 pt-0.5">
          {[
            { label: '天气', value: weatherCodeCN },
            { label: '能见度', value: visibilityStr },
            { label: '风向', value: windDirCN },
            { label: '风速', value: windSpeedStr },
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
      </div>

      {/* Navigation grid: COG / HDG / 经度 / 纬度 */}
      <div
        className="grid grid-cols-2 gap-x-4 gap-y-2 pb-3"
        style={{ borderBottom: '0.5px solid color-mix(in oklch, var(--ink-500) 12%, transparent)' }}
      >
        {[
          { label: 'COG 航向', value: `${ownShip.dynamics.cog.toFixed(1)}°` },
          { label: 'HDG 艏向', value: `${ownShip.dynamics.hdg.toFixed(1)}°` },
          { label: '经度', value: `E ${ownShip.position.lon.toFixed(4)}°`, small: true },
          { label: '纬度', value: `N ${ownShip.position.lat.toFixed(4)}°`, small: true },
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
        style={{ borderBottom: '0.5px solid color-mix(in oklch, var(--ink-500) 12%, transparent)' }}
      >
        <div className="mb-1.5 flex items-center justify-between">
          <span className="text-[10px]" style={{ color: 'var(--ink-500)' }}>
            电力推进 · 电池
          </span>
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

    </div>
  );
}
