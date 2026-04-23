/**
 * MapContainer Component
 * Main map visualization with MapLibre GL JS and Deck.gl overlay
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { MapboxOverlay } from '@deck.gl/mapbox';
import type { Layer, Position } from '@deck.gl/core';
import { PathStyleExtension } from '@deck.gl/extensions';
import { IconLayer, PathLayer, PolygonLayer, ScatterplotLayer } from '@deck.gl/layers';

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
import { useMapSettingsStore } from '../../store/useMapSettingsStore';
import type { LonLat, OwnShip, RGBAColor, WeatherZoneContext } from '../../types/schema';

const CONTOUR_DEBOUNCE_MS = 300;
const WEATHER_FOG_SOURCE_ID = 'weather-fog-source';
const WEATHER_FOG_LAYER_ID = 'weather-fog-layer';
const WEATHER_FOG_COLOR = '#d2d7dc';
const WEATHER_FOG_MAX_OPACITY = 0.65;
const WEATHER_CLEAR_VISIBILITY_NM = 10.0;
const WEATHER_ZONE_SOURCE_PREFIX = 'weather-zone-';
const WEATHER_ZONE_LAYER_SUFFIX = '-layer';
const WEATHER_ZONE_SOURCE_SUFFIX = '-source';
const NAVIGATION_PLANE_Z = 48;
const CPA_BRIDGE_Z = 56;
const CPA_GLOW_Z = 58;
const CPA_POINT_Z = 60;
const OVERLAY_ON_TOP_PARAMETERS = {
  depthCompare: 'always',
  depthWriteEnabled: false,
  blend: true,
} as const;
const DECK_LAYER_ID_HINTS = new Set([
  'safety-domain',
  'traj-l-a', 'traj-l-b', 'traj-l-c',
  'traj-r-a', 'traj-r-b', 'traj-r-c',
  'target-traj-l', 'target-traj-r',
  'cpa-lines',
  'own-ship',
  'selected-target-highlight',
  'targets',
]);
const DECK_LAYER_ID_PREFIXES = [
  'safety-domain-',
  'own-traj-',
  'target-traj-',
  'cpa-',
] as const;

const WEATHER_FOG_POLYGON: GeoJSON.FeatureCollection<GeoJSON.Polygon> = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      geometry: {
        type: 'Polygon',
        coordinates: [[
          [-180, -85],
          [-180, 85],
          [180, 85],
          [180, -85],
          [-180, -85],
        ]],
      },
      properties: {},
    },
  ],
};


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
  const currentZoneIdsRef = useRef<Set<string>>(new Set());
  const [mapLoaded, setMapLoaded] = useState(false);
  const [debouncedSafetyContourVal, setDebouncedSafetyContourVal] = useState<number>(10);
  const { safetyContourOverride: localSafetyContourOverride } = useMapSettingsStore();

  const ownShip = useRiskStore(selectOwnShip);
  const targets = useRiskStore(selectTargets);
  const allTargets = targets;
  const environment = useRiskStore(selectEnvironment);
  const selectedTargetIds = useRiskStore((state) => state.selectedTargetIds);
  const selectTarget = useRiskStore((state) => state.selectTarget);
  const { isDarkMode } = useThemeStore();

  const liveSafetyContourVal = environment?.safety_contour_val ?? 10;
  const effectiveSafetyContourVal = localSafetyContourOverride ?? liveSafetyContourVal;
  const weatherZones = environment?.weather_zones;
  const fogOpacity = useMemo(
    () => {
      if (weatherZones != null && weatherZones.length > 0) return 0;
      return calculateFogOpacity(environment?.weather?.visibility_nm ?? null);
    },
    [environment?.weather?.visibility_nm, weatherZones],
  );
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
      ensureFogOverlayLayer(map.current, true);
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
      currentZoneIdsRef.current = new Set();
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
    ensureFogOverlayLayer(map.current, true);
    applyMapTheme(map.current, effectiveSafetyContourVal, isDarkMode);
    lastReloadedContourRef.current = debouncedSafetyContourVal;
  }, [debouncedSafetyContourVal, effectiveSafetyContourVal, isDarkMode, mapLoaded]);

  useEffect(() => {
    if (!mapLoaded || !map.current) {
      return;
    }

    ensureFogOverlayLayer(map.current, false);
    map.current.setPaintProperty(WEATHER_FOG_LAYER_ID, 'fill-opacity', fogOpacity);
  }, [fogOpacity, mapLoaded]);

  useEffect(() => {
    if (!mapLoaded || !map.current) {
      return;
    }
    const mapInstance = map.current;
    const zones = weatherZones ?? [];
    const activeZones = zones.filter((z) => z.weather_code !== 'CLEAR');
    const newZoneIds = new Set(activeZones.map((z) => z.zone_id));

    for (const oldId of currentZoneIdsRef.current) {
      if (!newZoneIds.has(oldId)) {
        const layerId = `${WEATHER_ZONE_SOURCE_PREFIX}${oldId}${WEATHER_ZONE_LAYER_SUFFIX}`;
        const sourceId = `${WEATHER_ZONE_SOURCE_PREFIX}${oldId}${WEATHER_ZONE_SOURCE_SUFFIX}`;
        if (mapInstance.getLayer(layerId)) mapInstance.removeLayer(layerId);
        if (mapInstance.getSource(sourceId)) mapInstance.removeSource(sourceId);
      }
    }

    const beforeId = findDeckLayerAnchor(mapInstance);
    for (const zone of activeZones) {
      const sourceId = `${WEATHER_ZONE_SOURCE_PREFIX}${zone.zone_id}${WEATHER_ZONE_SOURCE_SUFFIX}`;
      const layerId = `${WEATHER_ZONE_SOURCE_PREFIX}${zone.zone_id}${WEATHER_ZONE_LAYER_SUFFIX}`;
      const feature: GeoJSON.Feature = {
        type: 'Feature',
        geometry: zone.geometry as unknown as GeoJSON.Geometry,
        properties: {},
      };
      const { color, opacity } = getWeatherZoneStyle(zone.weather_code);

      const existingSource = mapInstance.getSource(sourceId) as maplibregl.GeoJSONSource | undefined;
      if (existingSource) {
        existingSource.setData(feature);
      } else {
        mapInstance.addSource(sourceId, { type: 'geojson', data: feature });
      }

      if (mapInstance.getLayer(layerId)) {
        mapInstance.setPaintProperty(layerId, 'fill-color', color);
        mapInstance.setPaintProperty(layerId, 'fill-opacity', opacity);
      } else {
        mapInstance.addLayer(
          { id: layerId, type: 'fill', source: sourceId, paint: { 'fill-color': color, 'fill-opacity': opacity } },
          beforeId,
        );
      }
    }

    currentZoneIdsRef.current = newZoneIds;
  }, [weatherZones, mapLoaded]);

  const buildDeckLayers = useCallback(() => {
    const layers: Layer[] = [];

    if (!ownShip) return layers;

    const ownPos: LonLat = [ownShip.position.lon, ownShip.position.lat];

    // 1. Safety Domain - Frosted Field with Glowing Edge
    if (ownShip.safety_domain.shape_type === 'ellipse' && ownShip.safety_domain.dimensions) {
      const domainPolygon = generateEllipsePolygon(
        ownPos,
        ownShip.safety_domain.dimensions,
        ownShip.dynamics.hdg,
        160,
      );

      // Inner Frosted Fill
      layers.push(
        new PolygonLayer({
          id: 'safety-domain-fill',
          data: [{ polygon: domainPolygon }],
          getPolygon: (d: { polygon: LonLat[] }) => d.polygon,
          getFillColor: [255, 255, 255, 25] as RGBAColor,
          getLineColor: [0, 0, 0, 0],
          pickable: false,
          parameters: OVERLAY_ON_TOP_PARAMETERS,
        }),
      );

      // Soft Glowing Boundary
      layers.push(
        new PathLayer({
          id: 'safety-domain-glow',
          data: [{ path: domainPolygon }],
          getPath: (d: { path: Position[] }) => d.path,
          getColor: [100, 180, 240, 80] as RGBAColor,
          getWidth: 8,
          widthUnits: 'pixels',
          jointRounded: true,
          capRounded: true,
          parameters: OVERLAY_ON_TOP_PARAMETERS,
        }),
      );

      // Sharp Inner Edge (High Detail)
      layers.push(
        new PathLayer({
          id: 'safety-domain-edge',
          data: [{ path: domainPolygon }],
          getPath: (d: { path: Position[] }) => d.path,
          getColor: [200, 230, 255, 120] as RGBAColor,
          getWidth: 1.5,
          widthUnits: 'pixels',
          jointRounded: true,
          capRounded: true,
          parameters: OVERLAY_ON_TOP_PARAMETERS,
        }),
      );
    }

    // 2. Trajectories - Glowing Ribbons
    // Own Ship Trajectory
    const ownTrajectoryPoints = buildTrajectoryPoints(ownShip);
    if (ownTrajectoryPoints.length > 1) {
      const n = ownTrajectoryPoints.length;
      const ownColor = (a: number): RGBAColor =>
        isDarkMode ? [56, 189, 248, a] : [20, 120, 200, a];

      // Wide Ribbon Base
      layers.push(new PathLayer<{ path: Position[] }>({
        id: 'own-traj-ribbon',
        data: [{ path: ownTrajectoryPoints.map((p) => [p[0], p[1], NAVIGATION_PLANE_Z] as Position) }],
        getPath: (d) => d.path,
        getColor: ownColor(40),
        getWidth: 12,
        widthUnits: 'pixels',
        jointRounded: true,
        capRounded: true,
        parameters: OVERLAY_ON_TOP_PARAMETERS,
      }));

      // Sharp Flow Line
      layers.push(new PathLayer<{ path: Position[] }>({
        id: 'own-traj-flow',
        data: [{ path: ownTrajectoryPoints.map((p) => [p[0], p[1], NAVIGATION_PLANE_Z] as Position) }],
        getPath: (d) => d.path,
        getColor: ownColor(180),
        getWidth: 2,
        widthUnits: 'pixels',
        parameters: OVERLAY_ON_TOP_PARAMETERS,
      }));

      // Distance Fading segments (simulate gradient)
      const ptsNear = ownTrajectoryPoints.slice(0, Math.max(2, Math.ceil(n * 0.3)));
      layers.push(new PathLayer<{ path: Position[] }>({
        id: 'own-traj-highlight',
        data: [{ path: ptsNear.map((p) => [p[0], p[1], NAVIGATION_PLANE_Z] as Position) }],
        getPath: (d) => d.path,
        getColor: ownColor(255),
        getWidth: 3.5,
        widthUnits: 'pixels',
        parameters: OVERLAY_ON_TOP_PARAMETERS,
      }));
    }

    // Target Trajectories
    allTargets.forEach((target) => {
      const traj = target.predicted_trajectory;
      if (!traj?.points || traj.points.length < 1) return;
      
      const fullPath: LonLat[] = [
        [target.position.lon, target.position.lat],
        ...traj.points.map((p): LonLat => [p.lon, p.lat]),
      ];
      
      const color = getRiskColor(target.risk_assessment.risk_level);
      const targetColor = (a: number): RGBAColor => [...color, a];

      layers.push(new PathLayer({
        id: `target-traj-ribbon-${target.id}`,
        data: [{ path: fullPath.map((p) => [p[0], p[1], NAVIGATION_PLANE_Z] as Position) }],
        getPath: (d) => d.path,
        getColor: targetColor(35),
        getWidth: 8,
        widthUnits: 'pixels',
        jointRounded: true,
        capRounded: true,
        parameters: OVERLAY_ON_TOP_PARAMETERS,
      }));

      layers.push(new PathLayer({
        id: `target-traj-flow-${target.id}`,
        data: [{ path: fullPath.map((p) => [p[0], p[1], NAVIGATION_PLANE_Z] as Position) }],
        getPath: (d) => d.path,
        getColor: targetColor(140),
        getWidth: 1.5,
        widthUnits: 'pixels',
        parameters: OVERLAY_ON_TOP_PARAMETERS,
      }));
    });

    // 3. CPA - Collision Bridge (Data-Link Style)
    targets.forEach((target) => {
      const riskLevel = target.risk_assessment.risk_level;
      const cpaLine = target.risk_assessment.graphic_cpa_line;
      if ((riskLevel === 'ALARM' || riskLevel === 'WARNING') && cpaLine) {
        const { own_pos, target_pos } = cpaLine;
        const color = getRiskColor(riskLevel);
        const cpaPositions = [own_pos, target_pos];
        const bridgePath: Position[] = [
          [own_pos[0], own_pos[1], CPA_BRIDGE_Z] as Position,
          [target_pos[0], target_pos[1], CPA_BRIDGE_Z] as Position,
        ];

        // Heatmap Glow at CPA Points
        cpaPositions.forEach((pos, idx) => {
          layers.push(new ScatterplotLayer<{ position: LonLat }>({
            id: `cpa-glow-${target.id}-${idx}`,
            data: [{ position: pos }],
            getPosition: (d) => [d.position[0], d.position[1], CPA_GLOW_Z],
            getRadius: 60,
            radiusUnits: 'meters',
            getFillColor: [...color, 40] as RGBAColor,
            stroked: false,
          }));
          
          layers.push(new ScatterplotLayer<{ position: LonLat }>({
            id: `cpa-point-${target.id}-${idx}`,
            data: [{ position: pos }],
            getPosition: (d) => [d.position[0], d.position[1], CPA_POINT_Z],
            getRadius: 12,
            radiusUnits: 'meters',
            getFillColor: [...color, 255] as RGBAColor,
            stroked: true,
            getLineColor: [255, 255, 255, 200],
            getLineWidth: 2,
            lineWidthUnits: 'pixels',
          }));
        });

        // Bridge Connection Glow
        layers.push(new PathLayer<{ path: Position[] }>({
          id: `cpa-bridge-glow-${target.id}`,
          data: [{ path: bridgePath }],
          getPath: (d) => d.path,
          getColor: [...color, 70] as RGBAColor,
          getWidth: 9,
          widthUnits: 'pixels',
          jointRounded: true,
          capRounded: true,
        }));

        // Bridge Connection Core
        layers.push(new PathLayer({
          id: `cpa-bridge-${target.id}`,
          data: [{ path: bridgePath }],
          getPath: (d: { path: Position[] }) => d.path,
          getColor: [...color, 230] as RGBAColor,
          getWidth: 3,
          widthUnits: 'pixels',
          dashJustified: true,
          getDashArray: [4, 3],
          jointRounded: true,
          capRounded: true,
          extensions: [new PathStyleExtension({ dash: true })],
        } as any));
      }
    });

    // Vessel Icons
    layers.push(
      new IconLayer({
        id: 'own-ship',
        data: [{ position: ownPos, heading: ownShip.dynamics.hdg }],
        getPosition: (d: { position: LonLat }) => [d.position[0], d.position[1], NAVIGATION_PLANE_Z],
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
            getPosition: (d: { position: LonLat }) => [d.position[0], d.position[1], NAVIGATION_PLANE_Z],
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
          getPosition: (d) => [d.position[0], d.position[1], NAVIGATION_PLANE_Z],
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
    if (map.current && mapLoaded) {
      ensureFogOverlayLayer(map.current, false);
    }
  }, [buildDeckLayers, mapLoaded]);

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

    </div>
  );
}

function buildTrajectoryPoints(ownShip: OwnShip): LonLat[] {
  const pos: LonLat = [ownShip.position.lon, ownShip.position.lat];

  return generateLinearTrajectory(pos, ownShip.dynamics.cog, ownShip.dynamics.sog, 4);
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

function ensureFogOverlayLayer(mapInstance: maplibregl.Map, allowMoveToTopWhenNoAnchor: boolean): void {
  const beforeId = findDeckLayerAnchor(mapInstance);

  if (!mapInstance.getSource(WEATHER_FOG_SOURCE_ID)) {
    mapInstance.addSource(WEATHER_FOG_SOURCE_ID, {
      type: 'geojson',
      data: WEATHER_FOG_POLYGON,
    });
  }

  if (!mapInstance.getLayer(WEATHER_FOG_LAYER_ID)) {
    mapInstance.addLayer({
      id: WEATHER_FOG_LAYER_ID,
      type: 'fill',
      source: WEATHER_FOG_SOURCE_ID,
      paint: {
        'fill-color': WEATHER_FOG_COLOR,
        'fill-opacity': 0,
      },
    }, beforeId);
    return;
  }

  if (beforeId) {
    mapInstance.moveLayer(WEATHER_FOG_LAYER_ID, beforeId);
    return;
  }

  if (allowMoveToTopWhenNoAnchor) {
    mapInstance.moveLayer(WEATHER_FOG_LAYER_ID);
  }
}

function findDeckLayerAnchor(mapInstance: maplibregl.Map): string | undefined {
  const styleLayers = mapInstance.getStyle()?.layers ?? [];
  for (const layer of styleLayers) {
    if (
      layer.id.startsWith('deckgl')
      || DECK_LAYER_ID_HINTS.has(layer.id)
      || DECK_LAYER_ID_PREFIXES.some((prefix) => layer.id.startsWith(prefix))
    ) {
      return layer.id;
    }
  }
  return undefined;
}

function calculateFogOpacity(visibilityNm: number | null): number {
  if (visibilityNm == null || Number.isNaN(visibilityNm)) {
    return 0;
  }

  const clampedVisibility = Math.min(WEATHER_CLEAR_VISIBILITY_NM, Math.max(0, visibilityNm));
  return ((WEATHER_CLEAR_VISIBILITY_NM - clampedVisibility) / WEATHER_CLEAR_VISIBILITY_NM) * WEATHER_FOG_MAX_OPACITY;
}

function getWeatherZoneStyle(weatherCode: WeatherZoneContext['weather_code']): { color: string; opacity: number } {
  switch (weatherCode) {
    case 'FOG': return { color: '#d2d7dc', opacity: 0.50 };
    case 'RAIN': return { color: '#4a6fa5', opacity: 0.35 };
    case 'STORM': return { color: '#2c2c4a', opacity: 0.55 };
    case 'SNOW': return { color: '#e8eef5', opacity: 0.40 };
    default: return { color: '#ffffff', opacity: 0 };
  }
}
