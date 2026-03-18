/**
 * MapContainer Component
 * Main map visualization with MapLibre GL JS and Deck.gl overlay
 */

import { useRef, useEffect, useCallback, useState } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { MapboxOverlay } from '@deck.gl/mapbox';
import type { Layer, Position } from '@deck.gl/core';
import { IconLayer, PathLayer, PolygonLayer, LineLayer } from '@deck.gl/layers';

import { useRiskStore, selectOwnShip, selectTargets, selectAllTargets, selectEnvironment } from '../../store';
import { 
  DEFAULT_VIEW_STATE, 
  MAP_CONSTRAINTS, 
  MVT_CONFIG,
  COLORS_RGB,
  COLORS_RGBA,
  getRiskColor,
  VISUALIZATION,
} from '../../config';
import { s57Sources, s57Layers, updateWaterDepthStyle } from '../../config/layerStyles';
import { 
  generateEllipsePolygon, 
  generateCurvedHeadline, 
  generateLinearTrajectory,
  generateOZTSector,
} from '../../utils';
import { WAYPOINTS, getRemainingWaypoints, TARGET_WAYPOINTS, getTargetRemainingWaypoints, isTargetInTrackingRange } from '../../services/mockDataGenerator';
import type { LonLat, Target, OwnShip, RGBAColor } from '../../types/schema';

type PathDatum = {
  path: Position[];
  color?: RGBAColor;
};

// Enhanced Vessel icon SVG with 3D effect (gradient + shadow)
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

// Enhanced Target icon SVG with 3D effect
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
  
  // Subscribe to store
  const ownShip = useRiskStore(selectOwnShip);
  const targets = useRiskStore(selectTargets);        // For tracking panel (nearby)
  const allTargets = useRiskStore(selectAllTargets);  // For map rendering (all ships)
  const environment = useRiskStore(selectEnvironment);
  
  // Initialize map
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
    
    // Add navigation controls
    map.current.addControl(new maplibregl.NavigationControl(), 'top-right');
    
    // Initialize Deck.gl overlay
    deckOverlay.current = new MapboxOverlay({
      interleaved: true,
      layers: [],
    });
    map.current.addControl(deckOverlay.current as unknown as maplibregl.IControl);
    
    map.current.on('load', () => {
      if (!map.current) return;
      

      // Add S-57 MVT sources (one per layer for compatibility)
      Object.entries(s57Sources).forEach(([sourceId, sourceSpec]) => {
        map.current!.addSource(sourceId, sourceSpec);
      });
      
      // Add S-57 layers
      s57Layers.forEach(layer => {
        map.current!.addLayer(layer);
      });

      setMapLoaded(true);
    });
    
    return () => {
      map.current?.remove();
      map.current = null;
    };
  }, []);
  
  // Update water depth style when safety contour changes
  useEffect(() => {
    if (!mapLoaded || !map.current || !environment) return;
    updateWaterDepthStyle(map.current, environment.safety_contour_val);
    }, [mapLoaded, environment?.safety_contour_val]);
    

  // Build Deck.gl layers
  const buildDeckLayers = useCallback(() => {
    const layers: Layer[] = [];
    
    if (!ownShip) return layers;
    
    const ownPos: LonLat = [ownShip.position.lon, ownShip.position.lat];
    
    // --- LAYER GROUP 1: POLYGONS (Bottom) ---

    // 1. Safety Domain Layer (Z=0, Sea Level)
    if (ownShip.safety_domain.shape_type === 'ellipse' && ownShip.safety_domain.dimensions) {
      const domainPolygon = generateEllipsePolygon(
        ownPos,
        ownShip.safety_domain.dimensions,
        ownShip.dynamics.hdg
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
          // Critical: Transparent background layer must NOT write to depth buffer
          // This prevents depth precision artifacts at low zoom (far distance)
          parameters: {
            depthMask: false,
          },
        })
      );
    }

    // 2. OZT Sectors (Risk Polygons) - REMOVED per user request
    // Rendered before icons/lines so they appear underneath
    /* 
    if (targets.length > 0) {
      const oztData = buildOZTData(targets, ownPos);
      if (oztData.length > 0) {
        layers.push(
          new PolygonLayer({
            id: 'ozt-sectors',
            data: oztData,
            getPolygon: (d: { polygon: LonLat[] }) => d.polygon,
            getFillColor: COLORS_RGBA.OZT_SECTOR,
            getLineColor: COLORS_RGB.ALARM,
            getLineWidth: 1,
            lineWidthUnits: 'pixels',
          })
        );
      }
    }
    */

    // --- LAYER GROUP 2: TRAJECTORIES (Middle) ---

    // 3. Own Ship Trajectory - HARDCODED WAYPOINT ROUTE with fade effect
    // Shows planned route from current position to next 3 waypoints
    const upcomingWaypoints: LonLat[] = [];
    const trajectoryPath: LonLat[] = [ownPos, ...upcomingWaypoints];
    
    if (trajectoryPath.length > 1) {
      // Create multiple path segments with decreasing opacity for fade effect
      const fadeSegments: { path: Position[]; color: RGBAColor }[] = [];
      
      for (let i = 0; i < trajectoryPath.length - 1; i++) {
        const startPoint = trajectoryPath[i];
        const endPoint = trajectoryPath[i + 1];
        // Opacity decreases: 220 -> 150 -> 80 -> 40
        const opacity = Math.max(40, 220 - i * 70);
        
        fadeSegments.push({
          path: [
            [startPoint[0], startPoint[1], 10],
            [endPoint[0], endPoint[1], 10]
          ],
          color: [0, 255, 255, opacity] as RGBAColor, // Cyan with fade
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
        })
      );
      
      // Add waypoint markers
      layers.push(
        new IconLayer({
          id: 'waypoint-markers',
          data: upcomingWaypoints.map((wp, idx) => ({
            position: wp,
            index: idx,
          })),
          getPosition: (d: { position: LonLat }) => [d.position[0], d.position[1], 15],
          getIcon: () => ({
            url: `data:image/svg+xml;charset=utf-8,${encodeURIComponent(`
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24">
                <circle cx="12" cy="12" r="8" fill="none" stroke="#00FFFF" stroke-width="2" opacity="0.8"/>
                <circle cx="12" cy="12" r="3" fill="#00FFFF" opacity="0.9"/>
              </svg>
            `)}`,
            width: 24,
            height: 24,
            anchorY: 12,
          }),
          getSize: (d: { index: number }) => 20 - d.index * 4, // Decreasing size
          billboard: true,
        })
      );
    }

    // 4. Target Trajectories - Short prediction with fade effect (2 waypoints)
    // Only render for targets within tracking range (1.5 NM)
    if (allTargets.length > 0) {
      const targetRouteSegments: { path: Position[]; color: RGBAColor }[] = [];
      
      allTargets.forEach(t => {
        // Only draw route for targets in tracking range
        if (!isTargetInTrackingRange(t.id)) return;
        
        // Get remaining waypoints for this target (use only next 2 waypoints)
        const waypoints = getTargetRemainingWaypoints(t.id);
        if (!waypoints || waypoints.length < 2) return;
        
        const nextTwoWaypoints = waypoints.slice(0, 2);
        const currentPos: LonLat = [t.position.lon, t.position.lat];
        const fullPath: LonLat[] = [currentPos, ...nextTwoWaypoints];
        
        for (let i = 0; i < fullPath.length - 1; i++) {
          // Fade: first segment bright, second segment dimmer
          const opacity = i === 0 ? 160 : 90;
          targetRouteSegments.push({
            path: [
              [fullPath[i][0], fullPath[i][1], 8],
              [fullPath[i + 1][0], fullPath[i + 1][1], 8]
            ],
            color: [255, 165, 0, opacity] as RGBAColor,  // Orange with fade
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
          })
        );
      }
    }

    // 5. CPA Lines (Z=12, above trajectories)
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
          })
        );
      }
    }

    // --- LAYER GROUP 3: ICONS (Top) ---

    // 6. Own Ship Icon Layer (Z=50, Top Layer)
    const ownShipColor = getOwnShipColor(ownShip);
    layers.push(
      new IconLayer({
        id: 'own-ship',
        data: [{ position: ownPos, heading: ownShip.dynamics.hdg }],
        // Lift ship icon 50 meters above sea level
        getPosition: (d: { position: LonLat }) => [d.position[0], d.position[1], 50],
        getIcon: () => ({
          url: VESSEL_ICON,
          width: 48,
          height: 48,
          anchorY: 24,
        }),
        getSize: VISUALIZATION.VESSEL_ICON_SIZE,
        getAngle: (d: { heading: number }) => -d.heading,
        getColor: ownShipColor,
        pickable: true,
        billboard: true,
      })
    );
    
    // 7. Target Icons (Z=50, Top Layer) - Render ALL ships, color by risk level
    if (allTargets.length > 0) {
      // Target icons - color changes based on risk: SAFE=green, CAUTION=yellow, WARNING=orange, ALARM=red
      layers.push(
        new IconLayer({
          id: 'targets',
          data: allTargets.map(t => ({
            position: [t.position.lon, t.position.lat] as LonLat,
            heading: t.vector.course_deg,
            riskLevel: t.risk_assessment.risk_level,
            id: t.id,
          })),
          // Lift target icons 50 meters above sea level
          getPosition: (d) => [d.position[0], d.position[1], 50],
          getIcon: () => ({
            url: TARGET_ICON,
            width: 48,
            height: 48,
            anchorY: 24,
          }),
          getSize: VISUALIZATION.VESSEL_ICON_SIZE * 0.8,
          getAngle: (d) => -d.heading,
          getColor: (d) => getRiskColor(d.riskLevel),  // Dynamic color: ALARM=red, WARNING=orange, etc.
          pickable: true,
          billboard: true,
        })
      );
    }
    
    return layers;
  }, [ownShip, targets, allTargets]);
  
  // Update Deck.gl layers when data changes
  useEffect(() => {
    if (!deckOverlay.current) return;
    const layers = buildDeckLayers();
    deckOverlay.current.setProps({ layers });
  }, [buildDeckLayers]);
  
  // Center map on own ship position
  useEffect(() => {
    if (!map.current || !ownShip) return;
    
    // Only fly to position on first load
    const currentCenter = map.current.getCenter();
    const distance = Math.sqrt(
      (currentCenter.lng - ownShip.position.lon) ** 2 +
      (currentCenter.lat - ownShip.position.lat) ** 2
    );
    
    if (distance > 0.1) {
      map.current.flyTo({
        center: [ownShip.position.lon, ownShip.position.lat],
        duration: 1000,
      });
    }
  }, [ownShip?.position.lon, ownShip?.position.lat]);
  
  return (
    <div 
      ref={mapContainer} 
      className="w-full h-full"
      style={{ minHeight: '100vh' }}
    />
  );
}

// Helper functions

function buildTrajectoryPoints(ownShip: OwnShip): LonLat[] {
  const pos: LonLat = [ownShip.position.lon, ownShip.position.lat];
  
  if (ownShip.future_trajectory.prediction_type === 'curved_headline' &&
      ownShip.future_trajectory.curved_headline) {
    const chl = ownShip.future_trajectory.curved_headline;
    return generateCurvedHeadline(
      pos,
      ownShip.dynamics.hdg,
      chl.turn_radius_nm,
      ownShip.dynamics.rot,
      chl.projected_time_min
    );
  }
  
  return generateLinearTrajectory(pos, ownShip.dynamics.cog, ownShip.dynamics.sog, 6);
}

function getOwnShipColor(ownShip: OwnShip): [number, number, number, number] {
  switch (ownShip.platform_health.status) {
    case 'NUC':
      return [124, 58, 237, 255]; // Purple
    case 'DEGRADED':
      return [245, 158, 11, 255]; // Yellow
    default:
      return [16, 185, 129, 255]; // Green
  }
}

function buildCPALineData(targets: Target[]): { source: LonLat; target: LonLat }[] {
  return targets
    .filter(t => 
      t.risk_assessment.risk_level === 'ALARM' && 
      t.risk_assessment.graphic_cpa_line
    )
    .map(t => ({
      source: t.risk_assessment.graphic_cpa_line!.own_pos,
      target: t.risk_assessment.graphic_cpa_line!.target_pos,
    }));
}

function buildOZTData(targets: Target[], ownPos: LonLat): { polygon: LonLat[]; id: string }[] {
  return targets
    .filter(t => 
      t.risk_assessment.ozt_sector?.is_active &&
      (t.risk_assessment.risk_level === 'WARNING' || t.risk_assessment.risk_level === 'ALARM')
    )
    .map(t => ({
      polygon: generateOZTSector(
        ownPos,
        t.risk_assessment.ozt_sector!.start_angle_deg,
        t.risk_assessment.ozt_sector!.end_angle_deg,
        0.5 // OZT radius in nm
      ),
      id: t.id,
    }));
}

