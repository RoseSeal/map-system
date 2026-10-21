/**
 * S-57 Chart Data Service
 * API client for S-57 ENC backend services
 */

import { MVT_CONFIG } from '../config';

export interface SafetyContourResponse {
  safetyContourDepth: number;
  defaultSafetyContourDepth: number;
  unit: string;
  description: string;
  tileUrl: string;
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

export async function updateSafetyContour(depth: number): Promise<SafetyContourResponse> {
  const response = await fetch(`${MVT_CONFIG.SAFETY_CONTOUR_URL}?depth=${encodeURIComponent(depth)}`, {
    method: 'PUT',
  });
  if (!response.ok) {
    throw new Error('Failed to update safety contour: ' + response.statusText);
  }
  return await response.json();
}

export async function resetSafetyContour(): Promise<SafetyContourResponse> {
  const response = await fetch(`${MVT_CONFIG.SAFETY_CONTOUR_URL}/reset`, {
    method: 'POST',
  });
  if (!response.ok) {
    throw new Error('Failed to reset safety contour: ' + response.statusText);
  }
  return await response.json();
}
