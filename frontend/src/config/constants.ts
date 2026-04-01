/**
 * Configuration Constants
 * Design tokens and system-wide configuration values
 */

import type { RGBColor, RGBAColor } from '../types/schema';

// ============================================================
// Color System (Design Tokens)
// ============================================================

/** Risk level colors */
export const COLORS = {
  // Risk levels
  SAFE: '#10B981' as const,
  CAUTION: '#F59E0B' as const,
  WARNING: '#F97316' as const,
  ALARM: '#EF4444' as const,

  // Maritime
  LAND: '#374151' as const,
  WATER_DEEP: '#111827' as const,
  WATER_SHALLOW: '#1E3A8A' as const,
  GHOST: '#9CA3AF' as const,

  // UI
  NUC_STATUS: '#7C3AED' as const, // Purple for NUC
  TRAJECTORY: '#10B981' as const,
} as const;

/** RGB arrays for Deck.gl layers */
export const COLORS_RGB: Record<string, RGBColor> = {
  SAFE: [16, 185, 129],
  CAUTION: [245, 158, 11],
  WARNING: [249, 115, 22],
  ALARM: [239, 68, 68],
  LAND: [55, 65, 81],
  WATER_DEEP: [17, 24, 39],
  WATER_SHALLOW: [30, 58, 138],
  GHOST: [156, 163, 175],
  NUC_STATUS: [124, 58, 237],
  TRAJECTORY: [16, 185, 129],
} as const;

/** RGBA arrays with alpha for Deck.gl layers */
export const COLORS_RGBA: Record<string, RGBAColor> = {
  SAFE: [16, 185, 129, 200],
  CAUTION: [245, 158, 11, 200],
  WARNING: [249, 115, 22, 200],
  ALARM: [239, 68, 68, 200],
  SAFETY_DOMAIN: [16, 185, 129, 80],
  OZT_SECTOR: [239, 68, 68, 120],
} as const;

/** Get risk color by level */
export function getRiskColor(level: string): RGBColor {
  switch (level) {
    case 'SAFE': return COLORS_RGB.SAFE;
    case 'CAUTION': return COLORS_RGB.CAUTION;
    case 'WARNING': return COLORS_RGB.WARNING;
    case 'ALARM': return COLORS_RGB.ALARM;
    default: return COLORS_RGB.SAFE;
  }
}

// ============================================================
// Map Configuration
// ============================================================

/** Default map view state */
export const DEFAULT_VIEW_STATE = {
  longitude: -73.835,  // Jamaica Bay center (updated)
  latitude: 40.615,    // Jamaica Bay center (updated)

  /**
  longitude: 114.216525, // wuhan
  latitude: 30.578373,  // wuhan
  */

  zoom: 12,
  pitch: 45, // 2.5D view
  bearing: 0,
} as const;

/** Map constraints */
export const MAP_CONSTRAINTS = {
  minZoom: 3,
  maxZoom: 18,
  minPitch: 0,
  maxPitch: 60,
} as const;

// ============================================================
// MVT Source Configuration
// ============================================================

/** Backend origins */
export const BACKEND_CONFIG = {
  API_HOST: 'localhost:8080',
  HTTP_ORIGIN: 'http://localhost:8080',
  WS_ORIGIN: 'ws://localhost:8080',
  S57_API_PATH: '/api/s57',
  RISK_SSE_PATH: '/api/v2/risk',
  CHAT_WS_PATH: '/api/v2/chat',
  RISK_SSE_URL: 'http://localhost:8080/api/v2/risk',
  CHAT_WS_URL: 'ws://localhost:8080/api/v2/chat',
} as const;

/** S-57 MVT tile source */
export const MVT_CONFIG = {
  API_BASE_URL: `${BACKEND_CONFIG.HTTP_ORIGIN}${BACKEND_CONFIG.S57_API_PATH}`,
  SOURCE_ID: 's57-source',
  TILE_BASE_URL: `${BACKEND_CONFIG.HTTP_ORIGIN}${BACKEND_CONFIG.S57_API_PATH}/tiles/{z}/{x}/{y}`,
  SOURCE_URL: `${BACKEND_CONFIG.HTTP_ORIGIN}${BACKEND_CONFIG.S57_API_PATH}/tiles/{z}/{x}/{y}.pbf`,
  STYLE_URL: `${BACKEND_CONFIG.HTTP_ORIGIN}${BACKEND_CONFIG.S57_API_PATH}/style.json`,
  LAYERS_URL: `${BACKEND_CONFIG.HTTP_ORIGIN}${BACKEND_CONFIG.S57_API_PATH}/layers`,
  SAFETY_CONTOUR_URL: `${BACKEND_CONFIG.HTTP_ORIGIN}${BACKEND_CONFIG.S57_API_PATH}/safety-contour`,
  HEALTH_URL: `${BACKEND_CONFIG.HTTP_ORIGIN}${BACKEND_CONFIG.S57_API_PATH}/health`,
} as const;

// ============================================================
// Layer Configuration
// ============================================================

/** Layer IDs for MapLibre */
export const LAYER_IDS = {
  LAND_BASE: 'land-base',
  LAND_3D: 'land-3d',
  WATER_DEPTH: 'water-depth',
  RESTRICTED: 'restricted',
} as const;

/** Layer visibility zoom thresholds */
export const LAYER_ZOOM = {
  LAND_3D_MIN: 10,  // Lowered from 13 to show 3D earlier
  RESTRICTED_MIN: 10,
  DEPTH_POINTS_MIN: 13,
} as const;

/** 3D extrusion settings */
export const EXTRUSION = {
  LAND_HEIGHT: 80,  // Increased from 20 for more dramatic 3D effect
  LAND_BASE: 0,
  LAND_OPACITY: 0.95,
} as const;

// ============================================================
// WebSocket Configuration
// ============================================================

export const WS_CONFIG = {
  URL: BACKEND_CONFIG.CHAT_WS_URL,
  HEARTBEAT_INTERVAL_MS: 30000,
  HEARTBEAT_TIMEOUT_MS: 10000,
  RECONNECT_BASE_DELAY_MS: 1000,
  RECONNECT_MAX_DELAY_MS: 30000,
  RECONNECT_MULTIPLIER: 2,
} as const;

export const CHAT_CONFIG = {
  MAX_AUDIO_SIZE_BYTES: 10 * 1024 * 1024,
  VOICE_SENT_RESET_DELAY_MS: 2000,
} as const;

// ============================================================
// Performance & Rendering
// ============================================================

export const PERFORMANCE = {
  /** Target FPS */
  TARGET_FPS: 60,
  /** Max targets before throttling */
  MAX_TARGETS: 1000,
  /** Dead reckoning update interval (ms) */
  INTERPOLATION_INTERVAL_MS: 100,
  /** Trust factor threshold for warning */
  LOW_TRUST_THRESHOLD: 0.4,
} as const;

// ============================================================
// Visualization Settings
// ============================================================

export const VISUALIZATION = {
  /** Safety domain minimum risk level to display */
  DOMAIN_MIN_RISK: 'CAUTION' as const,
  /** CPA line minimum risk level to display */
  CPA_LINE_MIN_RISK: 'ALARM' as const,
  /** Vessel icon size in pixels */
  VESSEL_ICON_SIZE: 48,
  /** Trajectory line width */
  TRAJECTORY_WIDTH: 3,
  /** CPA line width */
  CPA_LINE_WIDTH: 2,
  /** OZT sector radius in pixels */
  OZT_RADIUS: 100,
} as const;
