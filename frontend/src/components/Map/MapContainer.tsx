/**
 * MapContainer Component
 * Main map visualization with MapLibre GL JS and Deck.gl overlay
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { MapboxOverlay } from '@deck.gl/mapbox';
import type { Layer, Position } from '@deck.gl/core';
import { IconLayer, LineLayer, PathLayer, PolygonLayer, ScatterplotLayer } from '@deck.gl/layers';

import {
  useRiskStore,
  selectOwnShip,
  selectTargets,
  selectEnvironment,
} from '../../store';
import { useThemeStore } from '../../store/useThemeStore';
import {
  DEFAULT_VIEW_STATE,
  MAP_CONSTRAINTS,
  COLORS_RGB,
  COLORS_RGBA,
  getRiskColor,
  VISUALIZATION,
} from '../../config';
import {
  addS57Layers,
  addS57Sources,
  applyMapTheme,
  ensureObstructionIcons,
  removeS57Sources,
  syncHydrologyLayerVisibility,
} from '../../config/layerStyles';
import {
  generateEllipsePolygon,
  generateLinearTrajectory,
} from '../../utils';
import type { LonLat, OwnShip, RGBAColor, RiskTarget } from '../../types/schema';

const CONTOUR_MIN = 5;
const CONTOUR_MAX = 30;
const CONTOUR_DEBOUNCE_MS = 300;
const SAFETY_CONTOUR_PRESETS = [5, 10, 15, 20, 25, 30] as const;

function roundContourValue(value: number): number {
  return Number(value.toFixed(1));
}

function buildSafetyContourOptions(liveSafetyContourVal: number): number[] {
  const options = new Set<number>(SAFETY_CONTOUR_PRESETS);

  if (Number.isFinite(liveSafetyContourVal)) {
    options.add(roundContourValue(liveSafetyContourVal));
  }

  return [...options].sort((left, right) => left - right);
}

function findClosestContourIndex(value: number, options: number[]): number {
  return options.reduce((closestIndex, option, index) => (
    Math.abs(option - value) < Math.abs(options[closestIndex] - value)
      ? index
      : closestIndex
  ), 0);
}

const VESSEL_ICON = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(`
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" width="48" height="48">
  <defs>
    <linearGradient id="shipGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#34D399;stop-opacity:1" />
      <stop offset="100%" style="stop-color:#059669;stop-opacity:1" />
    </linearGradient>
    <filter id="shadow" x="-50%" y="-50%" width="200%" height="200%">
      <feDropShadow dx="2" dy="4" stdDeviation="3" flood-color="#000" flood-opacity="0.5"/>
    </filter>
  </defs>
  <path filter="url(#shadow)" fill="url(#shipGrad)" d="M24 4L8 40h32L24 4z"/>
  <ellipse cx="24" cy="22" rx="5" ry="4" fill="white" opacity="0.9"/>
  <path fill="none" stroke="white" stroke-width="1.5" d="M24 10L24 18" opacity="0.8"/>
</svg>
`)}`;

const TARGET_ICON = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(`
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" width="48" height="48">
  <defs>
    <linearGradient id="targetGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#FBBF24;stop-opacity:1" />
      <stop offset="100%" style="stop-color:#D97706;stop-opacity:1" />
    </linearGradient>
    <filter id="targetShadow" x="-50%" y="-50%" width="200%" height="200%">
      <feDropShadow dx="1" dy="3" stdDeviation="2" flood-color="#000" flood-opacity="0.4"/>
    </filter>
  </defs>
  <path filter="url(#targetShadow)" fill="url(#targetGrad)" d="M24 6L10 38h28L24 6z"/>
  <circle cx="24" cy="22" r="3" fill="white" opacity="0.8"/>
</svg>
`)}`;

export function MapContainer() {
  const mapContainer = useRef<HTMLDivElement>(null);
  const map = useRef<maplibregl.Map | null>(null);
  const deckOverlay = useRef<MapboxOverlay | null>(null);
  const lastReloadedContourRef = useRef<number | null>(null);
  const [mapLoaded, setMapLoaded] = useState(false);
  const [localSafetyContourOverride, setLocalSafetyContourOverride] = useState<number | null>(null);
  const [debouncedSafetyContourVal, setDebouncedSafetyContourVal] = useState<number>(10);

  const ownShip = useRiskStore(selectOwnShip);
  const targets = useRiskStore(selectTargets);
  const allTargets = targets;
  const environment = useRiskStore(selectEnvironment);
  const selectedTargetIds = useRiskStore((state) => state.selectedTargetIds);
  const selectTarget = useRiskStore((state) => state.selectTarget);
  const { isDarkMode } = useThemeStore();

  const liveSafetyContourVal = environment?.safety_contour_val ?? 10;
  const effectiveSafetyContourVal = localSafetyContourOverride ?? liveSafetyContourVal;
  const safetyContourOptions = useMemo(
    () => buildSafetyContourOptions(liveSafetyContourVal),
    [liveSafetyContourVal],
  );
  const safetyContourIndex = findClosestContourIndex(effectiveSafetyContourVal, safetyContourOptions);
  const glassClass = isDarkMode ? 'glass-vision-dark' : 'glass-vision';

  useEffect(() => {
    setDebouncedSafetyContourVal(effectiveSafetyContourVal);
  }, []);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => {
      setDebouncedSafetyContourVal(effectiveSafetyContourVal);
    }, CONTOUR_DEBOUNCE_MS);

    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [effectiveSafetyContourVal]);

  useEffect(() => {
    if (!mapContainer.current || map.current) return;

    const mapInstance = new maplibregl.Map({
      container: mapContainer.current,
      style: {
        version: 8,
        sources: {},
        layers: [
          {
            id: 'background',
            type: 'background',
            paint: { 'background-color': '#111827' },
          },
        ],
      },
      center: [DEFAULT_VIEW_STATE.longitude, DEFAULT_VIEW_STATE.latitude],
      zoom: DEFAULT_VIEW_STATE.zoom,
      pitch: DEFAULT_VIEW_STATE.pitch,
      bearing: DEFAULT_VIEW_STATE.bearing,
      minZoom: MAP_CONSTRAINTS.minZoom,
      maxZoom: MAP_CONSTRAINTS.maxZoom,
      minPitch: MAP_CONSTRAINTS.minPitch,
      maxPitch: MAP_CONSTRAINTS.maxPitch,
    });
    map.current = mapInstance;

    const syncPitchVisibility = () => {
      if (!map.current) {
        return;
      }
      syncHydrologyLayerVisibility(map.current);
    };

    mapInstance.addControl(new maplibregl.NavigationControl(), 'top-right');

    deckOverlay.current = new MapboxOverlay({
      interleaved: true,
      layers: [],
    });
    mapInstance.addControl(deckOverlay.current as unknown as maplibregl.IControl);

    mapInstance.on('load', () => {
      if (!map.current) {
        return;
      }

      ensureObstructionIcons(map.current);
      addS57Sources(map.current, effectiveSafetyContourVal);
      addS57Layers(map.current);
      applyMapTheme(map.current, effectiveSafetyContourVal, isDarkMode);
      syncPitchVisibility();
      lastReloadedContourRef.current = effectiveSafetyContourVal;
      setMapLoaded(true);
    });

    mapInstance.on('pitchend', syncPitchVisibility);

    return () => {
      mapInstance.off('pitchend', syncPitchVisibility);
      mapInstance.remove();
      map.current = null;
    };
  }, []);

  useEffect(() => {
    if (!mapLoaded || !map.current) {
      return;
    }

    applyMapTheme(map.current, effectiveSafetyContourVal, isDarkMode);
  }, [effectiveSafetyContourVal, isDarkMode, mapLoaded]);

  useEffect(() => {
    if (!mapLoaded || !map.current) {
      return;
    }

    if (lastReloadedContourRef.current === debouncedSafetyContourVal) {
      return;
    }

    removeS57Sources(map.current);
    ensureObstructionIcons(map.current);
    addS57Sources(map.current, debouncedSafetyContourVal);
    addS57Layers(map.current);
    applyMapTheme(map.current, effectiveSafetyContourVal, isDarkMode);
    lastReloadedContourRef.current = debouncedSafetyContourVal;
  }, [debouncedSafetyContourVal, effectiveSafetyContourVal, isDarkMode, mapLoaded]);

  const buildDeckLayers = useCallback(() => {
    const layers: Layer[] = [];

    if (!ownShip) return layers;

    const ownPos: LonLat = [ownShip.position.lon, ownShip.position.lat];

    if (ownShip.safety_domain.shape_type === 'ellipse' && ownShip.safety_domain.dimensions) {
      const domainPolygon = generateEllipsePolygon(
        ownPos,
        ownShip.safety_domain.dimensions,
        ownShip.dynamics.hdg,
      );

      layers.push(
        new PolygonLayer({
          id: 'safety-domain',
          data: [{ polygon: domainPolygon }],
          getPolygon: (d: { polygon: LonLat[] }) => d.polygon,
          getFillColor: COLORS_RGBA.SAFETY_DOMAIN,
          getLineColor: COLORS_RGB.SAFE,
          getLineWidth: 2,
          lineWidthUnits: 'pixels',
          pickable: false,
          parameters: {
            depthMask: false,
          },
        }),
      );
    }

    const trajectoryPoints = buildTrajectoryPoints(ownShip);
    if (trajectoryPoints.length > 1) {
      const fadeSegments: { path: Position[]; color: RGBAColor }[] = [];

      for (let i = 0; i < trajectoryPoints.length - 1; i += 1) {
        const opacity = Math.max(40, 220 - i * 50);
        fadeSegments.push({
          path: [
            [trajectoryPoints[i][0], trajectoryPoints[i][1], 10],
            [trajectoryPoints[i + 1][0], trajectoryPoints[i + 1][1], 10],
          ],
          color: [0, 255, 255, opacity] as RGBAColor,
        });
      }

      layers.push(
        new PathLayer<{ path: Position[]; color: RGBAColor }>({
          id: 'trajectory-fade',
          data: fadeSegments,
          getPath: (d) => d.path,
          getColor: (d) => d.color,
          getWidth: 4,
          widthUnits: 'pixels',
          pickable: false,
          capRounded: true,
          jointRounded: true,
        }),
      );
    }

    if (allTargets.length > 0) {
      const targetRouteSegments: { path: Position[]; color: RGBAColor }[] = [];

      allTargets.forEach((target) => {
        const traj = target.predicted_trajectory;
        if (!traj?.points || traj.points.length < 1) return;

        const fullPath: LonLat[] = [
          [target.position.lon, target.position.lat],
          ...traj.points.map((point): LonLat => [point.lon, point.lat]),
        ];

        for (let i = 0; i < fullPath.length - 1; i += 1) {
          const opacity = i === 0 ? 160 : 90;
          targetRouteSegments.push({
            path: [
              [fullPath[i][0], fullPath[i][1], 8],
              [fullPath[i + 1][0], fullPath[i + 1][1], 8],
            ],
            color: [255, 165, 0, opacity] as RGBAColor,
          });
        }
      });

      if (targetRouteSegments.length > 0) {
        layers.push(
          new PathLayer<{ path: Position[]; color: RGBAColor }>({
            id: 'target-trajectories-fade',
            data: targetRouteSegments,
            getPath: (d) => d.path,
            getColor: (d) => d.color,
            getWidth: 2,
            widthUnits: 'pixels',
            pickable: false,
            capRounded: true,
            jointRounded: true,
          }),
        );
      }
    }

    if (targets.length > 0) {
      const cpaLineData = buildCPALineData(targets);
      if (cpaLineData.length > 0) {
        layers.push(
          new LineLayer({
            id: 'cpa-lines',
            data: cpaLineData,
            getSourcePosition: (d: { source: LonLat }) => [d.source[0], d.source[1], 12],
            getTargetPosition: (d: { target: LonLat }) => [d.target[0], d.target[1], 12],
            getColor: COLORS_RGB.ALARM,
            getWidth: VISUALIZATION.CPA_LINE_WIDTH,
            widthUnits: 'pixels',
          }),
        );
      }
    }

    layers.push(
      new IconLayer({
        id: 'own-ship',
        data: [{ position: ownPos, heading: ownShip.dynamics.hdg }],
        getPosition: (d: { position: LonLat }) => [d.position[0], d.position[1], 50],
        getIcon: () => ({
          url: VESSEL_ICON,
          width: 48,
          height: 48,
          anchorY: 24,
        }),
        getSize: VISUALIZATION.VESSEL_ICON_SIZE,
        getAngle: (d: { heading: number }) => -d.heading,
        getColor: getOwnShipColor(ownShip),
        pickable: true,
        billboard: true,
      }),
    );

    if (allTargets.length > 0) {
      const selectedTargets = allTargets.filter((target) => selectedTargetIds.includes(target.id));
      if (selectedTargets.length > 0) {
        layers.push(
          new ScatterplotLayer({
            id: 'selected-target-highlight',
            data: selectedTargets.map((target) => ({
              position: [target.position.lon, target.position.lat] as LonLat,
            })),
            getPosition: (d: { position: LonLat }) => [d.position[0], d.position[1], 32],
            getRadius: 95,
            radiusUnits: 'meters',
            getFillColor: [0, 0, 0, 0],
            getLineColor: isDarkMode ? [56, 189, 248, 255] : [245, 158, 11, 255],
            getLineWidth: 4,
            lineWidthUnits: 'pixels',
            filled: false,
            stroked: true,
            pickable: false,
          }),
        );
      }

      layers.push(
        new IconLayer({
          id: 'targets',
          data: allTargets.map((target) => ({
            position: [target.position.lon, target.position.lat] as LonLat,
            heading: target.vector.course_deg,
            riskLevel: target.risk_assessment.risk_level,
            id: target.id,
          })),
          getPosition: (d) => [d.position[0], d.position[1], 50],
          getIcon: () => ({
            url: TARGET_ICON,
            width: 48,
            height: 48,
            anchorY: 24,
          }),
          getSize: VISUALIZATION.VESSEL_ICON_SIZE * 0.8,
          getAngle: (d) => -d.heading,
          getColor: (d) => getRiskColor(d.riskLevel),
          pickable: true,
          billboard: true,
          onClick: ({ object }) => {
            if (object?.id) {
              selectTarget(object.id);
            }
          },
        }),
      );
    }

    return layers;
  }, [allTargets, ownShip, selectedTargetIds, selectTarget, targets, isDarkMode]);

  useEffect(() => {
    if (!deckOverlay.current) return;
    deckOverlay.current.setProps({ layers: buildDeckLayers() });
  }, [buildDeckLayers]);

  useEffect(() => {
    if (!map.current || !ownShip) return;

    const currentCenter = map.current.getCenter();
    const distance = Math.sqrt(
      (currentCenter.lng - ownShip.position.lon) ** 2 +
      (currentCenter.lat - ownShip.position.lat) ** 2,
    );

    if (distance > 0.1) {
      map.current.flyTo({
        center: [ownShip.position.lon, ownShip.position.lat],
        duration: 1000,
      });
    }
  }, [ownShip]);

  return (
    <div className="relative h-full w-full" style={{ minHeight: '100vh' }}>
      <div
        ref={mapContainer}
        className="h-full w-full"
        style={{ minHeight: '100vh' }}
      />

      <div className="pointer-events-none absolute right-4 top-20 z-20">
        <div className={`${glassClass} pointer-events-auto w-[240px] rounded-[18px] px-4 py-3`}>
          <div className="flex items-start justify-between gap-3">
            <div>
              <div className="text-[10px] font-semibold uppercase tracking-[0.18em]" style={{ color: 'var(--ink-500)' }}>
                Hydrology
              </div>
              <div className="mt-1 text-[13px] font-semibold" style={{ color: 'var(--ink-900)' }}>
                Safety Contour
              </div>
            </div>
            <div
              className="rounded-full px-2 py-0.5 font-mono text-[11px]"
              style={{
                background: localSafetyContourOverride === null
                  ? 'color-mix(in oklch, var(--risk-safe) 12%, transparent)'
                  : 'color-mix(in oklch, var(--risk-warning) 12%, transparent)',
                color: localSafetyContourOverride === null ? 'var(--risk-safe)' : 'var(--risk-warning)',
              }}
            >
              {effectiveSafetyContourVal.toFixed(1)}m
            </div>
          </div>

          <div className="mt-3">
            <input
              aria-label="Safety contour value"
              type="range"
              min={0}
              max={Math.max(0, safetyContourOptions.length - 1)}
              step={1}
              value={safetyContourIndex}
              onChange={(event) => {
                const nextOption = safetyContourOptions[Number(event.target.value)];
                if (nextOption !== undefined) {
                  setLocalSafetyContourOverride(nextOption);
                }
              }}
              className="h-2 w-full cursor-pointer appearance-none rounded-full bg-transparent"
            />
            <div className="mt-1 flex justify-between text-[10px] font-mono" style={{ color: 'var(--ink-500)' }}>
              <span>{safetyContourOptions[0]?.toFixed(1) ?? CONTOUR_MIN.toFixed(1)}m</span>
              <span>{safetyContourOptions[safetyContourOptions.length - 1]?.toFixed(1) ?? CONTOUR_MAX.toFixed(1)}m</span>
            </div>
          </div>

          <div className="mt-3 flex items-center justify-between gap-3">
            <div className="text-[10px]" style={{ color: 'var(--ink-500)' }}>
              {localSafetyContourOverride === null
                ? `实时值 ${liveSafetyContourVal.toFixed(1)}m`
                : `已覆盖实时值 ${liveSafetyContourVal.toFixed(1)}m`}
            </div>
            <button
              type="button"
              disabled={localSafetyContourOverride === null}
              onClick={() => {
                setLocalSafetyContourOverride(null);
              }}
              className="rounded-full px-2.5 py-1 text-[10px] font-medium transition disabled:cursor-default disabled:opacity-40"
              style={{
                background: 'color-mix(in oklch, var(--ink-500) 10%, transparent)',
                color: 'var(--ink-700)',
              }}
            >
              恢复实时值
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function buildTrajectoryPoints(ownShip: OwnShip): LonLat[] {
  const pos: LonLat = [ownShip.position.lon, ownShip.position.lat];

  return generateLinearTrajectory(pos, ownShip.dynamics.cog, ownShip.dynamics.sog, 6);
}

function getOwnShipColor(ownShip: OwnShip): [number, number, number, number] {
  switch (ownShip.platform_health.status) {
    case 'NUC':
      return [124, 58, 237, 255];
    case 'DEGRADED':
      return [245, 158, 11, 255];
    default:
      return [16, 185, 129, 255];
  }
}

function buildCPALineData(targets: RiskTarget[]): { source: LonLat; target: LonLat }[] {
  return targets
    .filter((target) => target.risk_assessment.risk_level === 'ALARM' && target.risk_assessment.graphic_cpa_line)
    .map((target) => ({
      source: target.risk_assessment.graphic_cpa_line!.own_pos,
      target: target.risk_assessment.graphic_cpa_line!.target_pos,
    }));
}
