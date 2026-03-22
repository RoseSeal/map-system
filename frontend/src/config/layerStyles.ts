/**
 * MapLibre Layer Style Definitions
 * S-57 MVT layer styling for 2.5D maritime chart rendering
 */

import { COLORS, LAYER_IDS, LAYER_ZOOM, EXTRUSION, MVT_CONFIG } from './constants';
import type { LayerSpecification, SourceSpecification } from 'maplibre-gl';

/** Individual source for each layer (workaround for composite tile issues) */
export const s57Sources: Record<string, SourceSpecification> = {
  's57-depare': {
    type: 'vector',
    tiles: [`${MVT_CONFIG.TILE_BASE_URL}/DEPARE.pbf`],
    minzoom: 0,
    maxzoom: 14,
  },
  's57-lndare': {
    type: 'vector',
    tiles: [`${MVT_CONFIG.TILE_BASE_URL}/LNDARE.pbf`],
    minzoom: 0,
    maxzoom: 14,
  },
  's57-coalne': {
    type: 'vector',
    tiles: [`${MVT_CONFIG.TILE_BASE_URL}/COALNE.pbf`],
    minzoom: 0,
    maxzoom: 14,
  },
  's57-depcnt': {
    type: 'vector',
    tiles: [`${MVT_CONFIG.TILE_BASE_URL}/DEPCNT.pbf`],
    minzoom: 0,
    maxzoom: 14,
  },
  's57-soundg': {
    type: 'vector',
    tiles: [`${MVT_CONFIG.TILE_BASE_URL}/SOUNDG.pbf`],
    minzoom: 0,
    maxzoom: 14,
  },
};

/** MVT Source specification (legacy - for composite tile) */
export const s57Source: SourceSpecification = {
  type: 'vector',
  tiles: [MVT_CONFIG.SOURCE_URL],
  minzoom: 0,
  maxzoom: 14,
};

/** Land base fill layer (2D fallback) */
export const landBaseLayer: LayerSpecification = {
  id: LAYER_IDS.LAND_BASE,
  type: 'fill',
  source: 's57-lndare',
  'source-layer': 'LNDARE',
  minzoom: 0,
  maxzoom: LAYER_ZOOM.LAND_3D_MIN,
  paint: {
    'fill-color': COLORS.LAND,
    'fill-opacity': 1,
  },
};

/** Land 3D extrusion layer - enhanced with lighting effect */
export const land3DLayer: LayerSpecification = {
  id: LAYER_IDS.LAND_3D,
  type: 'fill-extrusion',
  source: 's57-lndare',
  'source-layer': 'LNDARE',
  minzoom: LAYER_ZOOM.LAND_3D_MIN,
  paint: {
    'fill-extrusion-color': '#2D3748',
    'fill-extrusion-height': EXTRUSION.LAND_HEIGHT,
    'fill-extrusion-base': EXTRUSION.LAND_BASE,
    'fill-extrusion-opacity': EXTRUSION.LAND_OPACITY,
    'fill-extrusion-vertical-gradient': true,
  },
};

/**
 * Water depth layer with enhanced gradient coloring
 * Uses multiple depth thresholds for visual depth perception
 */
export const waterDepthLayer: LayerSpecification = {
  id: LAYER_IDS.WATER_DEPTH,
  type: 'fill',
  source: 's57-depare',
  'source-layer': 'DEPARE',
  minzoom: 0,
  paint: {
    'fill-color': [
      'interpolate',
      ['linear'],
      ['coalesce', ['get', 'drval1'], 50],
      0, '#1E3A5F',
      5, '#1E3A8A',
      10, '#172554',
      20, '#0F172A',
      50, '#0A0F1A',
    ],
    'fill-opacity': 0.9,
  },
};

/** Depth contour lines - subtle, non-distracting */
export const depthContourLayer: LayerSpecification = {
  id: 'depth-contour',
  type: 'line',
  source: 's57-depcnt',
  'source-layer': 'DEPCNT',
  minzoom: 11,
  paint: {
    'line-color': '#4A90A4',
    'line-width': 0.8,
    'line-opacity': 0.4,
  },
};

/** Coastline layer - bright white for clear land/water boundary */
export const coastlineLayer: LayerSpecification = {
  id: 'coastline',
  type: 'line',
  source: 's57-coalne',
  'source-layer': 'COALNE',
  minzoom: 0,
  paint: {
    'line-color': '#F5F0E6',
    'line-width': [
      'interpolate',
      ['linear'],
      ['zoom'],
      8, 0.5,
      12, 1,
      16, 1.5,
    ],
    'line-opacity': 0.85,
  },
};

/** Sounding points layer */
export const soundingLayer: LayerSpecification = {
  id: 'soundings',
  type: 'circle',
  source: 's57-soundg',
  'source-layer': 'SOUNDG',
  minzoom: LAYER_ZOOM.DEPTH_POINTS_MIN,
  paint: {
    'circle-radius': 3,
    'circle-color': '#60A5FA',
    'circle-opacity': 0.8,
    'circle-stroke-width': 1,
    'circle-stroke-color': '#ffffff',
  },
};

/** Sounding labels layer */
export const soundingLabelLayer: LayerSpecification = {
  id: 'sounding-labels',
  type: 'symbol',
  source: 's57-soundg',
  'source-layer': 'SOUNDG',
  minzoom: LAYER_ZOOM.DEPTH_POINTS_MIN,
  layout: {
    'text-field': ['get', 'depth'],
    'text-font': ['Open Sans Regular'],
    'text-size': 10,
    'text-offset': [0, 1.5],
  },
  paint: {
    'text-color': '#ffffff',
    'text-halo-color': '#000000',
    'text-halo-width': 1,
  },
};

/** Restricted area layer (semi-transparent red) */
export const restrictedLayer: LayerSpecification = {
  id: LAYER_IDS.RESTRICTED,
  type: 'fill',
  source: MVT_CONFIG.SOURCE_ID,
  'source-layer': 'RESARE',
  minzoom: LAYER_ZOOM.RESTRICTED_MIN,
  paint: {
    'fill-color': COLORS.ALARM,
    'fill-opacity': 0.1,
  },
};

/** All S-57 layers for map initialization */
export const s57Layers: LayerSpecification[] = [
  waterDepthLayer,
  landBaseLayer,
  land3DLayer,
  coastlineLayer,
  depthContourLayer,
  soundingLayer,
  soundingLabelLayer,
  restrictedLayer,
];

/**
 * Update water depth layer based on dynamic safety contour value
 * @param map MapLibre map instance
 * @param safetyContourVal Safety contour depth in meters
 */
export function updateWaterDepthStyle(
  map: maplibregl.Map,
  safetyContourVal: number
): void {
  if (!map.getLayer(LAYER_IDS.WATER_DEPTH)) return;

  map.setPaintProperty(LAYER_IDS.WATER_DEPTH, 'fill-color', [
    'case',
    ['<', ['get', 'DRVAL1'], safetyContourVal],
    COLORS.WATER_SHALLOW,
    COLORS.WATER_DEEP,
  ]);
}
