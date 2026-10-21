/**
 * Geographic Utilities
 * Coordinate transformations, dead reckoning, and maritime calculations
 * 
 * NOTE: Uses Haversine Destination Point formula for accuracy.
 * This is an improvement over the flat-earth approximation in the original spec.
 */

import type { SafetyDomainDimensions, LonLat } from '../types/schema';

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

// ============================================================
// Trajectory Calculations
// ============================================================

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
