/**
 * S-57 Chart Data Service
 * API client for S-57 ENC backend services
 */

import { MVT_CONFIG } from '../config';

export interface LayerMetadata {
  id: string;
  type: 'fill' | 'line' | 'symbol' | 'fill-extrusion';
  minzoom?: number;
  maxzoom?: number;
  description: string;
  geometryType: string;
  attributes?: Record<string, unknown>;
}

export interface LayerMetadataResponse {
  version: string;
  layers: LayerMetadata[];
  crs: string;
  bounds: {
    minLon: number;
    minLat: number;
    maxLon: number;
    maxLat: number;
  };
}

export interface SafetyContourResponse {
  safetyContourDepth: number;
  unit: string;
  description: string;
  tileUrl: string;
}

/**
 * Fetch layer metadata from backend
 */
export async function fetchLayerMetadata(): Promise<LayerMetadataResponse | null> {
  try {
    const response = await fetch(MVT_CONFIG.LAYERS_URL);
    if (!response.ok) {
      throw new Error('Failed to fetch layer metadata: ' + response.statusText);
    }
    return await response.json();
  } catch (error) {
    console.error('Error fetching layer metadata:', error);
    return null;
  }
}

/**
 * Fetch style configuration from backend
 */
export async function fetchStyleConfig(): Promise<any> {
  try {
    const response = await fetch(MVT_CONFIG.STYLE_URL);
    if (!response.ok) {
      throw new Error('Failed to fetch style config: ' + response.statusText);
    }
    return await response.json();
  } catch (error) {
    console.error('Error fetching style config:', error);
    return null;
  }
}

/**
 * Get safety contour configuration
 * @param depth Optional safety depth in meters (default: 10.0)
 */
export async function getSafetyContour(depth?: number): Promise<SafetyContourResponse | null> {
  try {
    const url = depth !== undefined
      ? `${MVT_CONFIG.SAFETY_CONTOUR_URL}?depth=${depth}`
      : MVT_CONFIG.SAFETY_CONTOUR_URL;

    const response = await fetch(url);
    if (!response.ok) {
      throw new Error('Failed to fetch safety contour: ' + response.statusText);
    }
    return await response.json();
  } catch (error) {
    console.error('Error fetching safety contour:', error);
    return null;
  }
}

/**
 * Verify backend health
 */
export async function checkBackendHealth(): Promise<boolean> {
  try {
    const response = await fetch(MVT_CONFIG.HEALTH_URL);
    return response.ok;
  } catch (error) {
    console.error('Backend health check failed:', error);
    return false;
  }
}
