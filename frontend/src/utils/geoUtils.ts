/**
 * Geographic Utilities
 * Coordinate transformations, dead reckoning, and maritime calculations
 * 
 * NOTE: Uses Haversine Destination Point formula for accuracy.
 * This is an improvement over the flat-earth approximation in the original spec.
 */

import type { Position, SafetyDomainDimensions, LonLat } from '../types/schema';

// ============================================================
// Constants
// ============================================================

/** Earth radius in meters (WGS84 mean radius) */
const EARTH_RADIUS_M = 6371008.8;

/** Conversion: Nautical miles to meters */
const NM_TO_METERS = 1852;

/** Conversion: Knots to m/s */
const KNOTS_TO_MS = 0.514444;

/** Conversion: Degrees to radians */
const DEG_TO_RAD = Math.PI / 180;

/** Conversion: Radians to degrees */
const RAD_TO_DEG = 180 / Math.PI;

// ============================================================
// Unit Conversions
// ============================================================

/** Convert nautical miles to meters */
export function nmToMeters(nm: number): number {
  return nm * NM_TO_METERS;
}

/** Convert meters to nautical miles */
export function metersToNm(meters: number): number {
  return meters / NM_TO_METERS;
}

/** Convert knots to m/s */
export function knotsToMs(knots: number): number {
  return knots * KNOTS_TO_MS;
}

/** Convert m/s to knots */
export function msToKnots(ms: number): number {
  return ms / KNOTS_TO_MS;
}

/** Convert degrees to radians */
export function degToRad(deg: number): number {
  return deg * DEG_TO_RAD;
}

/** Convert radians to degrees */
export function radToDeg(rad: number): number {
  return rad * RAD_TO_DEG;
}

// ============================================================
// Dead Reckoning (Haversine Destination Point)
// ============================================================

/**
 * Calculate destination point using Haversine formula
 * More accurate than flat-earth approximation for maritime distances
 * 
 * @param start Starting position [longitude, latitude]
 * @param speedKnots Speed in knots
 * @param courseDeg Course in degrees (0 = North, clockwise)
 * @param timeDiffSec Time elapsed in seconds
 * @returns New position [longitude, latitude]
 */
export function deadReckon(
  start: LonLat,
  speedKnots: number,
  courseDeg: number,
  timeDiffSec: number
): LonLat {
  // Convert speed to distance in meters
  const distanceMeters = knotsToMs(speedKnots) * timeDiffSec;
  
  // Angular distance in radians
  const angularDistance = distanceMeters / EARTH_RADIUS_M;
  
  // Convert to radians
  const bearing = degToRad(courseDeg);
  const lat1 = degToRad(start[1]);
  const lon1 = degToRad(start[0]);
  
  // Haversine destination point formula
  const lat2 = Math.asin(
    Math.sin(lat1) * Math.cos(angularDistance) +
    Math.cos(lat1) * Math.sin(angularDistance) * Math.cos(bearing)
  );
  
  const lon2 = lon1 + Math.atan2(
    Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(lat1),
    Math.cos(angularDistance) - Math.sin(lat1) * Math.sin(lat2)
  );
  
  // Normalize longitude to -180 to +180
  const normalizedLon = ((radToDeg(lon2) + 540) % 360) - 180;
  
  return [normalizedLon, radToDeg(lat2)];
}

/**
 * Dead reckon from a Position object
 */
export function deadReckonFromPosition(
  position: Position,
  speedKnots: number,
  courseDeg: number,
  timeDiffSec: number
): Position {
  const [lon, lat] = deadReckon(
    [position.lon, position.lat],
    speedKnots,
    courseDeg,
    timeDiffSec
  );
  return { lon, lat };
}

// ============================================================
// Trajectory Calculations
// ============================================================

/**
 * Generate curved headline (CHL) points using circular arc approximation
 * 
 * @param start Starting position [lon, lat]
 * @param headingDeg Current heading in degrees
 * @param turnRadiusNm Turn radius in nautical miles
 * @param rotDegPerMin Rate of turn in degrees per minute
 * @param projectionTimeMin Projection time in minutes
 * @param numPoints Number of points to generate
 * @returns Array of [lon, lat] points forming the curved path
 */
export function generateCurvedHeadline(
  start: LonLat,
  headingDeg: number,
  turnRadiusNm: number,
  rotDegPerMin: number,
  projectionTimeMin: number,
  numPoints: number = 20
): LonLat[] {
  const points: LonLat[] = [start];
  
  if (Math.abs(rotDegPerMin) < 0.1) {
    // Effectively straight line
    return generateLinearTrajectory(start, headingDeg, 12, projectionTimeMin, numPoints);
  }
  
  const timeStepMin = projectionTimeMin / numPoints;
  let currentPos = start;
  let currentHeading = headingDeg;
  
  // Convert turn radius to angular change per time step
  const turnRatePerStep = rotDegPerMin * timeStepMin;
  
  for (let i = 1; i <= numPoints; i++) {
    // Update heading
    currentHeading = (currentHeading + turnRatePerStep + 360) % 360;
    
    // Calculate arc distance for this time step
    // Using circular motion: arc = radius * angle
    const arcAngleRad = degToRad(Math.abs(turnRatePerStep));
    const arcDistanceNm = turnRadiusNm * arcAngleRad;
    const arcDistanceM = nmToMeters(arcDistanceNm);
    
    // Average heading for this segment
    const avgHeading = currentHeading - turnRatePerStep / 2;
    
    // Project to next point
    const bearing = degToRad(avgHeading);
    const lat1 = degToRad(currentPos[1]);
    const lon1 = degToRad(currentPos[0]);
    const angularDist = arcDistanceM / EARTH_RADIUS_M;
    
    const lat2 = Math.asin(
      Math.sin(lat1) * Math.cos(angularDist) +
      Math.cos(lat1) * Math.sin(angularDist) * Math.cos(bearing)
    );
    
    const lon2 = lon1 + Math.atan2(
      Math.sin(bearing) * Math.sin(angularDist) * Math.cos(lat1),
      Math.cos(angularDist) - Math.sin(lat1) * Math.sin(lat2)
    );
    
    currentPos = [radToDeg(lon2), radToDeg(lat2)];
    points.push(currentPos);
  }
  
  return points;
}

/**
 * Generate linear trajectory points
 */
export function generateLinearTrajectory(
  start: LonLat,
  courseDeg: number,
  speedKnots: number,
  projectionTimeMin: number,
  numPoints: number = 10
): LonLat[] {
  const points: LonLat[] = [start];
  const timeStepSec = (projectionTimeMin * 60) / numPoints;
  
  let currentPos = start;
  for (let i = 1; i <= numPoints; i++) {
    currentPos = deadReckon(currentPos, speedKnots, courseDeg, timeStepSec);
    points.push(currentPos);
  }
  
  return points;
}

// ============================================================
// Safety Domain Geometry
// ============================================================

/**
 * Generate ellipse polygon vertices from ellipse dimensions
 * 
 * @param center Center position [lon, lat]
 * @param dimensions Ellipse dimensions in nautical miles
 * @param headingDeg Ship heading for ellipse orientation
 * @param numPoints Number of vertices (default 32)
 * @returns Array of [lon, lat] vertices forming the ellipse
 */
export function generateEllipsePolygon(
  center: LonLat,
  dimensions: SafetyDomainDimensions,
  headingDeg: number,
  numPoints: number = 64
): LonLat[] {
  const vertices: LonLat[] = [];
  const headingRad = degToRad(headingDeg);
  
  // Consume the backend's asymmetric fore/aft/port/stbd extents directly so
  // the rendered domain preserves the actual ship-relative envelope.
  const fore = nmToMeters(dimensions.fore_nm);
  const aft = nmToMeters(dimensions.aft_nm);
  const port = nmToMeters(dimensions.port_nm);
  const stbd = nmToMeters(dimensions.stbd_nm);
  
  for (let i = 0; i <= numPoints; i++) {
    const angle = (2 * Math.PI * i) / numPoints;
    const sinA = Math.sin(angle);
    const cosA = Math.cos(angle);
    const xRadius = sinA >= 0 ? stbd : port;
    const yRadius = cosA >= 0 ? fore : aft;
    const x = xRadius * sinA;
    const y = yRadius * cosA;
    const cosH = Math.cos(headingRad);
    const sinH = Math.sin(headingRad);
    const xRot = x * cosH + y * sinH;
    const yRot = -x * sinH + y * cosH;
    
    // Convert to geo coordinates
    const point = offsetToLatLon(center, xRot, yRot);
    vertices.push(point);
  }
  
  return vertices;
}

/**
 * Convert local offset (meters) to lat/lon position
 */
export function offsetToLatLon(
  center: LonLat,
  xMeters: number,
  yMeters: number
): LonLat {
  const latOffset = yMeters / EARTH_RADIUS_M;
  const lonOffset = xMeters / (EARTH_RADIUS_M * Math.cos(degToRad(center[1])));
  
  return [
    center[0] + radToDeg(lonOffset),
    center[1] + radToDeg(latOffset),
  ];
}

// ============================================================
// OZT Sector Geometry
// ============================================================

/**
 * Generate OZT (Object Obstacle Zone) sector polygon
 * 
 * @param center Center position [lon, lat]
 * @param startAngleDeg Start angle in degrees
 * @param endAngleDeg End angle in degrees
 * @param radiusNm Sector radius in nautical miles
 * @param numPoints Points per arc segment
 * @returns Array of [lon, lat] vertices forming the sector
 */
export function generateOZTSector(
  center: LonLat,
  startAngleDeg: number,
  endAngleDeg: number,
  radiusNm: number = 1.0,
  numPoints: number = 16
): LonLat[] {
  const vertices: LonLat[] = [center]; // Start at center
  
  const radiusM = nmToMeters(radiusNm);
  
  // Normalize angles
  let start = startAngleDeg % 360;
  let end = endAngleDeg % 360;
  if (end < start) end += 360;
  
  const angleStep = (end - start) / numPoints;
  
  for (let i = 0; i <= numPoints; i++) {
    const angle = start + angleStep * i;
    const angleRad = degToRad(angle);
    
    const x = radiusM * Math.sin(angleRad);
    const y = radiusM * Math.cos(angleRad);
    
    vertices.push(offsetToLatLon(center, x, y));
  }
  
  vertices.push(center); // Close the sector
  
  return vertices;
}

// ============================================================
// Distance & Bearing Calculations
// ============================================================

/**
 * Calculate distance between two points in nautical miles (Haversine)
 */
export function distanceNm(point1: LonLat, point2: LonLat): number {
  const lat1 = degToRad(point1[1]);
  const lat2 = degToRad(point2[1]);
  const dLat = lat2 - lat1;
  const dLon = degToRad(point2[0] - point1[0]);
  
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  
  return metersToNm(EARTH_RADIUS_M * c);
}

/**
 * Calculate initial bearing from point1 to point2 in degrees
 */
export function bearingDeg(point1: LonLat, point2: LonLat): number {
  const lat1 = degToRad(point1[1]);
  const lat2 = degToRad(point2[1]);
  const dLon = degToRad(point2[0] - point1[0]);
  
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) -
    Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  
  return (radToDeg(Math.atan2(y, x)) + 360) % 360;
}
