import type { AtonFeatureCollection, PlannedRoute } from '../types/schema';
import { BACKEND_CONFIG } from '../config/constants';

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(`${BACKEND_CONFIG.NAVIGATION_API_URL}${path}`);
  if (!response.ok) {
    throw new Error(`GET ${path} failed: ${response.status}`);
  }
  return (await response.json()) as T;
}

export function fetchActiveRoute(): Promise<PlannedRoute> {
  return getJson<PlannedRoute>('/route/active');
}

export function fetchAtonNotices(): Promise<AtonFeatureCollection> {
  return getJson<AtonFeatureCollection>('/notices/aton');
}
