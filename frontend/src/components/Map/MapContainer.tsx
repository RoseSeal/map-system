/**
 * MapContainer Component
 * Main map visualization with MapLibre GL JS and Deck.gl overlay
 */
import { useRef, useEffect, useCallback, useState } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { MapboxOverlay } from '@deck.gl/mapbox';
import type { Layer, Position } from '@deck.gl/core';
import { IconLayer, PathLayer, PolygonLayer, LineLayer, ScatterplotLayer } from '@deck.gl/layers';

import {
  useRiskStore,
  selectOwnShip,
  selectTargets,
  selectEnvironment,
} from '../../store';
import {
  DEFAULT_VIEW_STATE,
  MAP_CONSTRAINTS,
  COLORS_RGB,
  COLORS_RGBA,
  getRiskColor,
  VISUALIZATION,
} from '../../config';
import { s57Sources, s57Layers, updateWaterDepthStyle } from '../../config/layerStyles';
import {
  generateEllipsePolygon,
  generateLinearTrajectory,
} from '../../utils';
import { getTargetRemainingWaypoints, isTargetInTrackingRange } from '../../services/mockDataGenerator';
import type { LonLat, RiskTarget, OwnShip, RGBAColor } from '../../types/schema';

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
  const [mapLoaded, setMapLoaded] = useState(false);

  const ownShip = useRiskStore(selectOwnShip);
  const targets = useRiskStore(selectTargets);
  const allTargets = targets;
  const environment = useRiskStore(selectEnvironment);
  const selectedTargetId = useRiskStore((state) => state.selectedTargetId);
  const selectTarget = useRiskStore((state) => state.selectTarget);

  useEffect(() => {
    if (!mapContainer.current || map.current) return;

    map.current = new maplibregl.Map({
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

    map.current.addControl(new maplibregl.NavigationControl(), 'top-right');

    deckOverlay.current = new MapboxOverlay({
      interleaved: true,
      layers: [],
    });
    map.current.addControl(deckOverlay.current as unknown as maplibregl.IControl);

    map.current.on('load', () => {
      if (!map.current) return;

      Object.entries(s57Sources).forEach(([sourceId, sourceSpec]) => {
        map.current!.addSource(sourceId, sourceSpec);
      });

      s57Layers.forEach((layer) => {
        map.current!.addLayer(layer);
      });

      setMapLoaded(true);
    });

    return () => {
      map.current?.remove();
      map.current = null;
    };
  }, []);

  useEffect(() => {
    if (!mapLoaded || !map.current || !environment) return;
    updateWaterDepthStyle(map.current, environment.safety_contour_val);
  }, [environment, mapLoaded]);

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
        if (!isTargetInTrackingRange(target.id)) return;

        const waypoints = getTargetRemainingWaypoints(target.id);
        if (!waypoints || waypoints.length < 2) return;

        const fullPath: LonLat[] = [[target.position.lon, target.position.lat], ...waypoints.slice(0, 2)];

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
      const selectedTarget = allTargets.find((target) => target.id === selectedTargetId);
      if (selectedTarget) {
        layers.push(
          new ScatterplotLayer({
            id: 'selected-target-highlight',
            data: [{ position: [selectedTarget.position.lon, selectedTarget.position.lat] as LonLat }],
            getPosition: (d: { position: LonLat }) => [d.position[0], d.position[1], 32],
            getRadius: 95,
            radiusUnits: 'meters',
            getFillColor: [0, 0, 0, 0],
            getLineColor: [56, 189, 248, 220],
            getLineWidth: 3,
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
  }, [allTargets, ownShip, selectedTargetId, selectTarget, targets]);

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
    <div
      ref={mapContainer}
      className="w-full h-full"
      style={{ minHeight: '100vh' }}
    />
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

