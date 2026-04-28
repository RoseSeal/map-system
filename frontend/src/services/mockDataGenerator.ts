/**
 * Mock Data Generator
 * Generates realistic RiskObject data for UI development without backend
 * 
 * UPDATED: Uses same waypoints as MQTT simulator (DroneSimulator.java)
 * to ensure ship stays in Jamaica Bay safe waters
 */

import type { EnvironmentUpdatePayload, PlatformHealthStatus, RiskLevel, RiskTarget, RiskUpdatePayload } from '../types/schema';
import { deadReckon } from '../utils/geoUtils';
import type { LonLat } from '../types/schema';
import { useRiskStore } from '../store/useRiskStore';

export const WAYPOINTS: LonLat[] = [
  [-73.866624, 40.592792],
  [-73.859365, 40.616033],
  [-73.847233, 40.619305],
  [-73.835084, 40.640587],
  [-73.816614, 40.639087],
  [-73.805607, 40.598807],
  [-73.821623, 40.590491],
  [-73.83791, 40.58708],
];

export const TARGET_WAYPOINTS: LonLat[][] = [
  [
    [-73.88870, 40.61980],
    [-73.86295, 40.63440],
    [-73.86052, 40.61412],
    [-73.88222, 40.60926],
  ],
  [
    [-73.86031, 40.61372],
    [-73.84389, 40.61818],
    [-73.85241, 40.62710],
    [-73.86031, 40.62345],
  ],
  [
    [-73.84003, 40.63359],
    [-73.83253, 40.64353],
    [-73.81915, 40.63967],
    [-73.82503, 40.64576],
    [-73.83943, 40.64130],
  ],
  [
    [-73.80941, 40.61331],
    [-73.80657, 40.63237],
    [-73.79319, 40.63055],
    [-73.79988, 40.61757],
    [-73.80799, 40.61088],
  ],
  [
    [-73.82746, 40.60074],
    [-73.83679, 40.60337],
    [-73.84612, 40.59830],
    [-73.83436, 40.59912],
  ],
];

const BASE_POSITION: LonLat = [...WAYPOINTS[0]];

let frameCount = 0;
let ownShipPosition: LonLat = [...BASE_POSITION];
let ownShipHeading = 45;
let currentWaypointIndex = 1;
let targets: MockTarget[] = [];
let updateInterval: ReturnType<typeof setInterval> | null = null;

const SHIP_SPEED_KN = 25;

interface MockTarget {
  id: string;
  position: LonLat;
  speed: number;
  course: number;
  riskLevel: RiskLevel;
  waypointIndex: number;
  routeIndex: number;
}

function initTargets(): void {
  targets = [
    { id: '413999001', position: [...TARGET_WAYPOINTS[0][0]], speed: 8, course: 0, riskLevel: 'SAFE', waypointIndex: 1, routeIndex: 0 },
    { id: '413999002', position: [...TARGET_WAYPOINTS[1][0]], speed: 7, course: 0, riskLevel: 'SAFE', waypointIndex: 1, routeIndex: 1 },
    { id: '413999003', position: [...TARGET_WAYPOINTS[2][0]], speed: 6, course: 0, riskLevel: 'SAFE', waypointIndex: 1, routeIndex: 2 },
    { id: '413999004', position: [...TARGET_WAYPOINTS[3][0]], speed: 5, course: 0, riskLevel: 'SAFE', waypointIndex: 1, routeIndex: 3 },
    { id: '413999005', position: [...TARGET_WAYPOINTS[4][0]], speed: 9, course: 0, riskLevel: 'SAFE', waypointIndex: 1, routeIndex: 4 },
  ];

  targets.forEach((target) => {
    const route = TARGET_WAYPOINTS[target.routeIndex];
    target.course = calculateBearing(target.position, route[target.waypointIndex]);
  });
}

function calculateBearing(from: LonLat, to: LonLat): number {
  const dLon = (to[0] - from[0]) * Math.PI / 180;
  const lat1 = from[1] * Math.PI / 180;
  const lat2 = to[1] * Math.PI / 180;

  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);

  const bearing = Math.atan2(y, x) * 180 / Math.PI;
  return (bearing + 360) % 360;
}

function calculateDistance(from: LonLat, to: LonLat): number {
  const R = 3440.065;
  const dLat = (to[1] - from[1]) * Math.PI / 180;
  const dLon = (to[0] - from[0]) * Math.PI / 180;
  const lat1 = from[1] * Math.PI / 180;
  const lat2 = to[1] * Math.PI / 180;

  const a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
    + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

  return R * c;
}

function updatePositions(deltaSeconds: number): void {
  const targetWaypoint = WAYPOINTS[currentWaypointIndex];
  ownShipHeading = calculateBearing(ownShipPosition, targetWaypoint);
  ownShipPosition = deadReckon(ownShipPosition, SHIP_SPEED_KN, ownShipHeading, deltaSeconds);

  const distToWaypoint = calculateDistance(ownShipPosition, targetWaypoint);
  if (distToWaypoint < 0.05) {
    currentWaypointIndex = (currentWaypointIndex + 1) % WAYPOINTS.length;
    console.log(`[Mock] Reached waypoint, heading to waypoint ${currentWaypointIndex + 1}`);
  }

  targets.forEach((target) => {
    const route = TARGET_WAYPOINTS[target.routeIndex];
    const targetWaypointRef = route[target.waypointIndex];

    target.course = calculateBearing(target.position, targetWaypointRef);
    target.position = deadReckon(target.position, target.speed, target.course, deltaSeconds);

    const distToTargetWaypoint = calculateDistance(target.position, targetWaypointRef);
    if (distToTargetWaypoint < 0.03) {
      target.waypointIndex = (target.waypointIndex + 1) % route.length;
    }

    const dist = calculateDistance(target.position, ownShipPosition);
    if (dist < 0.15) {
      target.riskLevel = 'ALARM';
    } else if (dist < 0.3) {
      target.riskLevel = 'WARNING';
    } else if (dist < 0.6) {
      target.riskLevel = 'CAUTION';
    } else {
      target.riskLevel = 'SAFE';
    }
  });
}

function getHealthStatus(): { status: PlatformHealthStatus; description: string } {
  const cycle = Math.floor(frameCount / 100) % 10;

  if (cycle === 9) {
    return { status: 'NUC', description: 'Propulsion Loss' };
  }
  if (cycle === 8) {
    return { status: 'DEGRADED', description: 'Sensor Calibration Required' };
  }

  return { status: 'NORMAL', description: 'All systems nominal' };
}

function generateRiskObject(): RiskUpdatePayload {
  frameCount += 1;

  const healthStatus = getHealthStatus();
  const rotValue = Math.sin(frameCount * 0.1) * 2;
  const trackingRangeNm = 1.5;
  const nearbyTargets = targets.filter((target) => calculateDistance(ownShipPosition, target.position) <= trackingRangeNm);

  const convertToTarget = (target: MockTarget): RiskTarget => {
    const dist = calculateDistance(ownShipPosition, target.position);
    const dcpa = dist * 0.3 + Math.random() * 0.1;
    const tcpa = 60 + Math.random() * 300;
    const ownCpaPos = deadReckon(ownShipPosition, 12.5, ownShipHeading, tcpa);
    const targetCpaPos = deadReckon(target.position, target.speed, target.course, tcpa);

    return {
      id: target.id,
      tracking_status: 'tracking',
      position: { lat: target.position[1], lon: target.position[0] },
      vector: { speed_kn: target.speed, course_deg: target.course },
      risk_assessment: {
        risk_level: target.riskLevel,
        cpa_metrics: { dcpa_nm: dcpa, tcpa_sec: tcpa },
        graphic_cpa_line: {
          own_pos: ownCpaPos,
          target_pos: targetCpaPos,
        },
        ozt_sector: target.riskLevel === 'WARNING' || target.riskLevel === 'ALARM'
          ? {
              start_angle_deg: target.course - 10,
              end_angle_deg: target.course + 10,
              is_active: true,
            }
          : undefined,
      },
    };
  };

  const trackedTargets: RiskTarget[] = nearbyTargets.map(convertToTarget);

  return {
    event_id: `mock-event-${frameCount.toString().padStart(6, '0')}`,
    risk_object_id: `mock_${frameCount.toString().padStart(6, '0')}`,
    timestamp: new Date().toISOString(),
    environment_state_version: 1,
    governance: {
      mode: 'adaptive',
      trust_factor: 0.75 + Math.sin(frameCount * 0.05) * 0.2,
    },
    own_ship: {
      id: 'ownShip',
      position: { lat: ownShipPosition[1], lon: ownShipPosition[0] },
      dynamics: {
        sog: 12.5,
        cog: ownShipHeading,
        hdg: ownShipHeading,
        rot: rotValue,
      },
      platform_health: healthStatus,
      future_trajectory: {
        prediction_type: 'linear',
      },
      safety_domain: {
        shape_type: 'ellipse',
        dimensions: {
          fore_nm: 0.2,
          aft_nm: 0.04,
          port_nm: 0.08,
          stbd_nm: 0.08,
        },
      },
    },
    targets: trackedTargets,
  };
}

function generateEnvironmentUpdate(): EnvironmentUpdatePayload {
  return {
    event_id: `mock-environment-${frameCount.toString().padStart(6, '0')}`,
    timestamp: new Date().toISOString(),
    environment_state_version: 1,
    reason: 'OWN_SHIP_ENV_REEVALUATED',
    changed_fields: ['weather', 'hydrology', 'active_alerts'],
    environment_context: {
      safety_contour_val: 10.0,
      active_alerts: [],
      weather: null,
      hydrology: null,
    },
  };
}

export function startMockDataGenerator(intervalMs: number = 1000): void {
  if (updateInterval) {
    console.warn('[Mock] Generator already running');
    return;
  }

  console.log('[Mock] Starting mock data generator with Jamaica Bay waypoints');

  ownShipPosition = [...WAYPOINTS[0]];
  currentWaypointIndex = 1;
  ownShipHeading = calculateBearing(ownShipPosition, WAYPOINTS[1]);
  frameCount = 0;
  initTargets();

  useRiskStore.getState().setRiskUpdate(generateRiskObject());
  useRiskStore.getState().setEnvironmentUpdate(generateEnvironmentUpdate());

  updateInterval = setInterval(() => {
    updatePositions(intervalMs / 1000);
    useRiskStore.getState().setRiskUpdate(generateRiskObject());
    useRiskStore.getState().setEnvironmentUpdate(generateEnvironmentUpdate());
  }, intervalMs);
}

export function stopMockDataGenerator(): void {
  if (updateInterval) {
    clearInterval(updateInterval);
    updateInterval = null;
    console.log('[Mock] Stopped mock data generator');
  }
  useRiskStore.getState().reset();
}

export function isMockGeneratorRunning(): boolean {
  return updateInterval !== null;
}

export function getCurrentWaypointIndex(): number {
  return currentWaypointIndex;
}

export function getRemainingWaypoints(count: number = 3): LonLat[] {
  const result: LonLat[] = [];
  for (let i = 0; i < count && i < WAYPOINTS.length; i += 1) {
    const idx = (currentWaypointIndex + i) % WAYPOINTS.length;
    result.push(WAYPOINTS[idx]);
  }
  return result;
}

export function getTargetRemainingWaypoints(targetId: string): LonLat[] | null {
  const target = targets.find((item) => item.id === targetId);
  if (!target) return null;

  const route = TARGET_WAYPOINTS[target.routeIndex];
  const result: LonLat[] = [];

  for (let i = 0; i < route.length; i += 1) {
    const idx = (target.waypointIndex + i) % route.length;
    result.push(route[idx]);
  }
  result.push(route[target.waypointIndex]);

  return result;
}

export function getTargetRoute(targetId: string): LonLat[] | null {
  const target = targets.find((item) => item.id === targetId);
  if (!target) return null;

  return [...TARGET_WAYPOINTS[target.routeIndex]];
}

export function isTargetInTrackingRange(targetId: string): boolean {
  const target = targets.find((item) => item.id === targetId);
  if (!target) return false;

  const dist = calculateDistance(ownShipPosition, target.position);
  return dist <= 1.5;
}
