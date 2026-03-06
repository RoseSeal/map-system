/**
 * Mock Data Generator
 * Generates realistic RiskObject data for UI development without backend
 * 
 * UPDATED: Uses same waypoints as MQTT simulator (DroneSimulator.java)
 * to ensure ship stays in Jamaica Bay safe waters
 */

import type { RiskObject, Target, RiskLevel, HealthStatus } from '../types/schema';
import { deadReckon } from '../utils/geoUtils';
import type { LonLat } from '../types/schema';
import { useRiskStore } from '../store/useRiskStore';

// ============================================================
// Jamaica Bay Safe Waypoints (from MQTT Simulator)
// These waypoints are verified to be in navigable waters
// Exported for trajectory visualization
// ============================================================
export const WAYPOINTS: LonLat[] = [
  [-73.866624, 40.592792],  // Waypoint 1 - Start
  [-73.859365, 40.616033],  // Waypoint 2
  [-73.847233, 40.619305],  // Waypoint 3
  [-73.835084, 40.640587],  // Waypoint 4
  [-73.816614, 40.639087],  // Waypoint 5
  [-73.805607, 40.598807],  // Waypoint 6
  [-73.821623, 40.590491],  // Waypoint 7
  [-73.83791, 40.58708],    // Waypoint 8 - loops back to 1
];

// ============================================================
// Target Ship Waypoints (5 groups for 5 target ships)
// Each group defines a patrol route in Jamaica Bay
// ============================================================
export const TARGET_WAYPOINTS: LonLat[][] = [
  // Target 1 (T-101): Central patrol
  [
    [-73.88870, 40.61980],
    [-73.86295, 40.63440],
    [-73.86052, 40.61412],
    [-73.88222, 40.60926],
  ],
  // Target 2 (T-102): Eastern patrol
  [
    [-73.86031, 40.61372],
    [-73.84389, 40.61818],
    [-73.85241, 40.62710],
    [-73.86031, 40.62345],
  ],
  // Target 3 (T-103): Northeastern patrol
  [
    [-73.84003, 40.63359],
    [-73.83253, 40.64353],
    [-73.81915, 40.63967],
    [-73.82503, 40.64576],
    [-73.83943, 40.64130],
  ],
  // Target 4 (T-104): Far eastern patrol
  [
    [-73.80941, 40.61331],
    [-73.80657, 40.63237],
    [-73.79319, 40.63055],
    [-73.79988, 40.61757],
    [-73.80799, 40.61088],
  ],
  // Target 5 (T-105): Southern patrol
  [
    [-73.82746, 40.60074],
    [-73.83679, 40.60337],
    [-73.84612, 40.59830],
    [-73.83436, 40.59912],
  ],
];

// Start at first waypoint
const BASE_POSITION: LonLat = [...WAYPOINTS[0]];

// Mock state
let frameCount = 0;
let ownShipPosition: LonLat = [...BASE_POSITION];
let ownShipHeading = 45; // Initial heading (will be calculated)
let currentWaypointIndex = 1; // Next waypoint to navigate to
let targets: MockTarget[] = [];
let updateInterval: ReturnType<typeof setInterval> | null = null;

// Ship speed in knots (faster for demo visual effect)
const SHIP_SPEED_KN = 25;

interface MockTarget {
  id: string;
  position: LonLat;
  speed: number;
  course: number;
  riskLevel: RiskLevel;
  waypointIndex: number;  // Current target waypoint
  routeIndex: number;     // Which route (0-4) this target follows
}

/**
 * Initialize mock targets (other vessels in the area)
 * 5 vessels each following their designated patrol route
 */
function initTargets(): void {
  targets = [
    {
      id: '413999001',
      position: [...TARGET_WAYPOINTS[0][0]],
      speed: 8,
      course: 0,
      riskLevel: 'SAFE',
      waypointIndex: 1,
      routeIndex: 0,
    },
    {
      id: '413999002',
      position: [...TARGET_WAYPOINTS[1][0]],
      speed: 7,
      course: 0,
      riskLevel: 'SAFE',
      waypointIndex: 1,
      routeIndex: 1,
    },
    {
      id: '413999003',
      position: [...TARGET_WAYPOINTS[2][0]],
      speed: 6,
      course: 0,
      riskLevel: 'SAFE',
      waypointIndex: 1,
      routeIndex: 2,
    },
    {
      id: '413999004',
      position: [...TARGET_WAYPOINTS[3][0]],
      speed: 5,
      course: 0,
      riskLevel: 'SAFE',
      waypointIndex: 1,
      routeIndex: 3,
    },
    {
      id: '413999005',
      position: [...TARGET_WAYPOINTS[4][0]],
      speed: 9,
      course: 0,
      riskLevel: 'SAFE',
      waypointIndex: 1,
      routeIndex: 4,
    },
  ];
  
  // Initialize course for each target towards their first waypoint
  targets.forEach(t => {
    const route = TARGET_WAYPOINTS[t.routeIndex];
    t.course = calculateBearing(t.position, route[t.waypointIndex]);
  });
}

/**
 * Calculate bearing between two points
 */
function calculateBearing(from: LonLat, to: LonLat): number {
  const dLon = (to[0] - from[0]) * Math.PI / 180;
  const lat1 = from[1] * Math.PI / 180;
  const lat2 = to[1] * Math.PI / 180;
  
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  
  let bearing = Math.atan2(y, x) * 180 / Math.PI;
  return (bearing + 360) % 360;
}

/**
 * Calculate distance between two points in nautical miles
 */
function calculateDistance(from: LonLat, to: LonLat): number {
  const R = 3440.065; // Earth radius in NM
  const dLat = (to[1] - from[1]) * Math.PI / 180;
  const dLon = (to[0] - from[0]) * Math.PI / 180;
  const lat1 = from[1] * Math.PI / 180;
  const lat2 = to[1] * Math.PI / 180;
  
  const a = Math.sin(dLat/2) * Math.sin(dLat/2) +
            Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon/2) * Math.sin(dLon/2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
  
  return R * c;
}

/**
 * Update mock positions - WAYPOINT FOLLOWING LOGIC
 */
function updatePositions(deltaSeconds: number): void {
  const targetWaypoint = WAYPOINTS[currentWaypointIndex];
  
  // Calculate bearing to next waypoint
  ownShipHeading = calculateBearing(ownShipPosition, targetWaypoint);
  
  // Move towards waypoint
  ownShipPosition = deadReckon(ownShipPosition, SHIP_SPEED_KN, ownShipHeading, deltaSeconds);
  
  // Check if we've reached the waypoint (within 0.05 NM = ~100 meters)
  const distToWaypoint = calculateDistance(ownShipPosition, targetWaypoint);
  if (distToWaypoint < 0.05) {
    // Move to next waypoint (loop back to start)
    currentWaypointIndex = (currentWaypointIndex + 1) % WAYPOINTS.length;
    console.log(`[Mock] Reached waypoint, heading to waypoint ${currentWaypointIndex + 1}`);
  }
  
  // Update targets - WAYPOINT FOLLOWING LOGIC (same as own ship)
  targets.forEach(target => {
    const route = TARGET_WAYPOINTS[target.routeIndex];
    const targetWaypoint = route[target.waypointIndex];
    
    // Calculate bearing to next waypoint
    target.course = calculateBearing(target.position, targetWaypoint);
    
    // Move towards waypoint
    target.position = deadReckon(target.position, target.speed, target.course, deltaSeconds);
    
    // Check if reached waypoint (within 0.03 NM = ~55 meters)
    const distToWaypoint = calculateDistance(target.position, targetWaypoint);
    if (distToWaypoint < 0.03) {
      // Move to next waypoint (loop back to start)
      target.waypointIndex = (target.waypointIndex + 1) % route.length;
    }
    
    // Calculate distance to own ship for risk level
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

/**
 * Get simulated platform health status
 */
function getHealthStatus(): { status: HealthStatus; description: string } {
  // Simulate occasional degraded/NUC status
  const cycle = Math.floor(frameCount / 100) % 10;
  
  if (cycle === 9) {
    return { status: 'NUC', description: 'Propulsion Loss' };
  } else if (cycle === 8) {
    return { status: 'DEGRADED', description: 'Sensor Calibration Required' };
  }
  
  return { status: 'NORMAL', description: 'All systems nominal' };
}

/**
 * Generate a complete RiskObject
 */
function generateRiskObject(): RiskObject {
  frameCount++;
  
  const healthStatus = getHealthStatus();
  const rotValue = Math.sin(frameCount * 0.1) * 2; // Oscillating ROT
  
  // === PROXIMITY FILTER (TODO: Move to backend in refactor) ===
  // Only include targets within tracking range (1.5 NM) for the tracking panel
  const TRACKING_RANGE_NM = 1.5;
  const nearbyTargets = targets.filter(t => {
    const dist = calculateDistance(ownShipPosition, t.position);
    return dist <= TRACKING_RANGE_NM;
  });
  // === END PROXIMITY FILTER ===
  
  // Helper function to convert MockTarget to Target
  const convertToTarget = (t: MockTarget): Target => {
    const dist = calculateDistance(ownShipPosition, t.position);
    const dcpa = dist * 0.3 + Math.random() * 0.1;
    const tcpa = 60 + Math.random() * 300;
    const ownCpaPos = deadReckon(ownShipPosition, 12.5, ownShipHeading, tcpa);
    const targetCpaPos = deadReckon(t.position, t.speed, t.course, tcpa);
    
    return {
      id: t.id,
      tracking_status: 'tracking' as const,
      position: { lat: t.position[1], lon: t.position[0] },
      vector: { speed_kn: t.speed, course_deg: t.course },
      risk_assessment: {
        risk_level: t.riskLevel,
        cpa_metrics: { dcpa_nm: dcpa, tcpa_sec: tcpa },
        graphic_cpa_line: {
          own_pos: ownCpaPos,
          target_pos: targetCpaPos,
        },
        ozt_sector: t.riskLevel === 'WARNING' || t.riskLevel === 'ALARM' 
          ? {
              start_angle_deg: t.course - 10,
              end_angle_deg: t.course + 10,
              is_active: true,
            }
          : undefined,
      },
    };
  };
  
  // Tracked targets (for panel) - only nearby ships
  const mockTargets: Target[] = nearbyTargets.map(convertToTarget);
  
  // All targets (for map rendering) - all ships regardless of distance
  const allTargetsForMap: Target[] = targets.map(convertToTarget);
  
  return {
    risk_object_id: `mock_${frameCount.toString().padStart(6, '0')}`,
    timestamp: new Date().toISOString(),
    governance: {
      mode: 'adaptive',
      trust_factor: 0.75 + Math.sin(frameCount * 0.05) * 0.2, // Oscillates 0.55 - 0.95
    },
    own_ship: {
      mmsi: '413000000',
      position: { lat: ownShipPosition[1], lon: ownShipPosition[0] },
      dynamics: {
        sog: 12.5,
        cog: ownShipHeading,
        hdg: ownShipHeading,
        rot: rotValue,
      },
      platform_health: healthStatus,
      future_trajectory: {
        // Keep UI stable in mock: backend will override with real prediction
        prediction_type: 'linear',
        curved_headline: undefined,
      },
      safety_domain: {
        shape_type: 'ellipse',
        dimensions: {
          fore_nm: 0.5,
          aft_nm: 0.1,
          port_nm: 0.2,
          stbd_nm: 0.2,
        },
      },
    },
    targets: mockTargets,           // For tracking panel (nearby only)
    all_targets: allTargetsForMap,  // For map rendering (all ships)
    simulation_layer: {
      is_active: false,
      ghost_ships: [],
    },
    environment_context: {
      safety_contour_val: 10.0,
      active_alerts: [],
    },
  };
}

/**
 * Start mock data generation
 * @param intervalMs Update interval in milliseconds (default 1000ms = 1Hz)
 */
export function startMockDataGenerator(intervalMs: number = 1000): void {
  if (updateInterval) {
    console.warn('[Mock] Generator already running');
    return;
  }
  
  console.log('[Mock] Starting mock data generator with Jamaica Bay waypoints');
  
  // Initialize - start at first waypoint, heading to second
  ownShipPosition = [...WAYPOINTS[0]];
  currentWaypointIndex = 1;
  ownShipHeading = calculateBearing(ownShipPosition, WAYPOINTS[1]);
  frameCount = 0;
  initTargets();
  
  // Mark as connected
  useRiskStore.getState().setConnectionStatus(true);
  
  // Generate first frame immediately
  useRiskStore.getState().setRiskObject(generateRiskObject());
  
  // Start update loop
  updateInterval = setInterval(() => {
    updatePositions(intervalMs / 1000);
    const riskObject = generateRiskObject();
    useRiskStore.getState().setRiskObject(riskObject);
  }, intervalMs);
}

/**
 * Stop mock data generation
 */
export function stopMockDataGenerator(): void {
  if (updateInterval) {
    clearInterval(updateInterval);
    updateInterval = null;
    console.log('[Mock] Stopped mock data generator');
  }
  useRiskStore.getState().setConnectionStatus(false);
}

/**
 * Check if mock generator is running
 */
export function isMockGeneratorRunning(): boolean {
  return updateInterval !== null;
}

/**
 * Get current waypoint index for trajectory rendering
 */
export function getCurrentWaypointIndex(): number {
  return currentWaypointIndex;
}

/**
 * Get remaining waypoints from current position (for trajectory display)
 * Returns up to 'count' waypoints starting from current target
 */
export function getRemainingWaypoints(count: number = 3): LonLat[] {
  const result: LonLat[] = [];
  for (let i = 0; i < count && i < WAYPOINTS.length; i++) {
    const idx = (currentWaypointIndex + i) % WAYPOINTS.length;
    result.push(WAYPOINTS[idx]);
  }
  return result;
}

/**
 * Get target ship's remaining waypoints for trajectory display
 * Returns the full route from current waypoint onwards (loops back)
 */
export function getTargetRemainingWaypoints(targetId: string): LonLat[] | null {
  const target = targets.find(t => t.id === targetId);
  if (!target) return null;
  
  const route = TARGET_WAYPOINTS[target.routeIndex];
  const result: LonLat[] = [];
  
  // Include all waypoints starting from current waypoint index
  for (let i = 0; i < route.length; i++) {
    const idx = (target.waypointIndex + i) % route.length;
    result.push(route[idx]);
  }
  // Add first waypoint again to complete the loop
  result.push(route[target.waypointIndex]);
  
  return result;
}

/**
 * Get target ship's full patrol route
 */
export function getTargetRoute(targetId: string): LonLat[] | null {
  const target = targets.find(t => t.id === targetId);
  if (!target) return null;
  
  return [...TARGET_WAYPOINTS[target.routeIndex]];
}

/**
 * Check if a target is within tracking range (1.5 NM)
 */
export function isTargetInTrackingRange(targetId: string): boolean {
  const target = targets.find(t => t.id === targetId);
  if (!target) return false;
  
  const dist = calculateDistance(ownShipPosition, target.position);
  return dist <= 1.5;  // 1.5 NM tracking range
}

