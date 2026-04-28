/**
 * MapLibre Layer Style Definitions
 * S-57 MVT layer styling for hydrology-focused maritime chart rendering
 */

import {
  COLORS,
  EXTRUSION,
  LAYER_IDS,
  LAYER_ZOOM,
  MVT_CONFIG,
} from './constants';
import type {
  ExpressionSpecification,
  LayerSpecification,
  Map as MapLibreMap,
  SourceSpecification,
} from 'maplibre-gl';

type MapThemeMode = 'dark' | 'light';
type ThemePaintUpdate = {
  layerId: string;
  paintProperty: string;
  value: string | boolean;
};

const LIGHT_THEME_BACKGROUND = '#F0F4F8';
const LIGHT_THEME_WATER_DEEP = '#8FB7D4';
const LIGHT_THEME_WATER_SHALLOW = '#6AADD0';
const LIGHT_THEME_HAZARD_FILL = '#D96A3A';
const LIGHT_THEME_LAND = '#E5E1D6';
const LIGHT_THEME_LAND_3D = '#D8D3C5';
const LIGHT_THEME_COASTLINE = '#8CA09A';
const LIGHT_THEME_DEPTH_CONTOUR = '#6E94AF';
const LIGHT_THEME_SOUNDING = '#5A7C96';
const LIGHT_THEME_SOUNDING_HALO = '#FFFFFF';

const DARK_THEME_WATER_DEEP = '#0B1D2E';
const DARK_THEME_WATER_SHALLOW = '#1E4D72';
const DARK_THEME_HAZARD_FILL = '#E87A40';

const SOURCE_IDS = {
  DEPARE: 's57-depare',
  LNDARE: 's57-lndare',
  COALNE: 's57-coalne',
  DEPCNT: 's57-depcnt',
  SOUNDG: 's57-soundg',
  OBSTRN: 's57-obstrn',
} as const;

const OBSTRUCTION_ICON_IDS = {
  wreck: 'obstrn-wreck',
  rock: 'obstrn-rock',
  pile: 'obstrn-pile',
  unknown: 'obstrn-unknown',
} as const;

function createDepthValueExpression(): ExpressionSpecification {
  return ['coalesce', ['get', 'drval1'], ['get', 'DRVAL1'], 50];
}

function createDangerAreaFilter(safetyContourVal: number): ExpressionSpecification {
  return ['<', createDepthValueExpression(), safetyContourVal];
}

function createSafeDepthAreaFilter(safetyContourVal: number): ExpressionSpecification {
  return ['>=', createDepthValueExpression(), safetyContourVal];
}

function createDepthExtrusionHeightExpression(): ExpressionSpecification {
  // Shallow areas protrude more; deeper areas approach zero height.
  // max(0, 40 - depth * exaggeration) keeps expression non-negative.
  return ['max', 0, ['-', 40, ['*', EXTRUSION.DEPTH_EXAGGERATION, createDepthValueExpression()]]];
}

function createObstructionIconExpression(): ExpressionSpecification {
  return [
    'match',
    ['coalesce', ['to-string', ['get', 'catobs']], ['to-string', ['get', 'CATOBS']], 'UNKNOWN'],
    ['1', '2', '3', 'WRECK', 'wreck', 'WRECKS', 'wrecks'],
    OBSTRUCTION_ICON_IDS.wreck,
    ['5', '6', '7', 'ROCK', 'rock', 'ROCKS', 'rocks'],
    OBSTRUCTION_ICON_IDS.rock,
    ['4', '8', '9', 'PILE', 'pile', 'STUMP', 'stump'],
    OBSTRUCTION_ICON_IDS.pile,
    OBSTRUCTION_ICON_IDS.unknown,
  ];
}

function formatSafetyContourValue(safetyContourVal: number): string {
  return safetyContourVal.toFixed(1);
}

function buildLayerTileUrl(layerName: string, safetyContourVal?: number): string {
  if (safetyContourVal === undefined) {
    return `${MVT_CONFIG.TILE_BASE_URL}/${layerName}.pbf`;
  }

  return `${MVT_CONFIG.TILE_BASE_URL}/${layerName}.pbf?safety_contour=${encodeURIComponent(formatSafetyContourValue(safetyContourVal))}`;
}

const MAP_THEME_PAINTS: Record<MapThemeMode, ThemePaintUpdate[]> = {
  dark: [
    { layerId: 'background', paintProperty: 'background-color', value: '#080C14' },
    { layerId: LAYER_IDS.LAND_BASE, paintProperty: 'fill-color', value: '#1E293B' },
    { layerId: LAYER_IDS.LAND_3D, paintProperty: 'fill-extrusion-color', value: '#24324D' },
    { layerId: LAYER_IDS.LAND_3D, paintProperty: 'fill-extrusion-vertical-gradient', value: true },
    { layerId: 'depth-contour', paintProperty: 'line-color', value: '#33667A' },
    { layerId: 'coastline', paintProperty: 'line-color', value: '#475569' },
    { layerId: 'soundings', paintProperty: 'circle-color', value: '#60A5FA' },
    { layerId: 'soundings', paintProperty: 'circle-stroke-color', value: '#ffffff' },
    { layerId: 'sounding-labels', paintProperty: 'text-color', value: '#ffffff' },
    { layerId: 'sounding-labels', paintProperty: 'text-halo-color', value: '#000000' },
  ],
  light: [
    { layerId: 'background', paintProperty: 'background-color', value: LIGHT_THEME_BACKGROUND },
    { layerId: LAYER_IDS.LAND_BASE, paintProperty: 'fill-color', value: LIGHT_THEME_LAND },
    { layerId: LAYER_IDS.LAND_3D, paintProperty: 'fill-extrusion-color', value: LIGHT_THEME_LAND_3D },
    { layerId: LAYER_IDS.LAND_3D, paintProperty: 'fill-extrusion-vertical-gradient', value: true },
    { layerId: 'depth-contour', paintProperty: 'line-color', value: LIGHT_THEME_DEPTH_CONTOUR },
    { layerId: 'coastline', paintProperty: 'line-color', value: LIGHT_THEME_COASTLINE },
    { layerId: 'soundings', paintProperty: 'circle-color', value: LIGHT_THEME_SOUNDING },
    { layerId: 'soundings', paintProperty: 'circle-stroke-color', value: '#ffffff' },
    { layerId: 'sounding-labels', paintProperty: 'text-color', value: LIGHT_THEME_SOUNDING },
    { layerId: 'sounding-labels', paintProperty: 'text-halo-color', value: LIGHT_THEME_SOUNDING_HALO },
  ],
};

const MAP_THEME_LIGHTS = {
  dark: {
    anchor: 'viewport' as const,
    color: '#E0F2FE',
    intensity: 0.45,
    position: [1.15, 210, 45] as [number, number, number],
  },
  light: {
    anchor: 'viewport' as const,
    color: '#FFFFFF',
    intensity: 0.15,
    position: [1.05, 165, 50] as [number, number, number],
  },
};

export function createS57Sources(safetyContourVal: number): Record<string, SourceSpecification> {
  return {
    [SOURCE_IDS.DEPARE]: {
      type: 'vector',
      tiles: [buildLayerTileUrl('DEPARE')],
      minzoom: 0,
      maxzoom: 14,
    },
    [SOURCE_IDS.LNDARE]: {
      type: 'vector',
      tiles: [buildLayerTileUrl('LNDARE')],
      minzoom: 0,
      maxzoom: 14,
    },
    [SOURCE_IDS.COALNE]: {
      type: 'vector',
      tiles: [buildLayerTileUrl('COALNE')],
      minzoom: 0,
      maxzoom: 14,
    },
    [SOURCE_IDS.DEPCNT]: {
      type: 'vector',
      tiles: [buildLayerTileUrl('DEPCNT', safetyContourVal)],
      minzoom: 0,
      maxzoom: 14,
    },
    [SOURCE_IDS.SOUNDG]: {
      type: 'vector',
      tiles: [buildLayerTileUrl('SOUNDG')],
      minzoom: 0,
      maxzoom: 14,
    },
    [SOURCE_IDS.OBSTRN]: {
      type: 'vector',
      tiles: [buildLayerTileUrl('OBSTRN')],
      minzoom: 0,
      maxzoom: 14,
    },
  };
}

const landBaseLayer: LayerSpecification = {
  id: LAYER_IDS.LAND_BASE,
  type: 'fill',
  source: SOURCE_IDS.LNDARE,
  'source-layer': 'LNDARE',
  minzoom: 0,
  maxzoom: LAYER_ZOOM.LAND_3D_MIN,
  paint: {
    'fill-color': COLORS.LAND,
    'fill-opacity': 1,
  },
};

const land3DLayer: LayerSpecification = {
  id: LAYER_IDS.LAND_3D,
  type: 'fill-extrusion',
  source: SOURCE_IDS.LNDARE,
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

const waterDepthFlatLayer: LayerSpecification = {
  id: LAYER_IDS.WATER_DEPTH_FLAT,
  type: 'fill',
  source: SOURCE_IDS.DEPARE,
  'source-layer': 'DEPARE',
  minzoom: 0,
  filter: createSafeDepthAreaFilter(10),
  paint: {
    'fill-color': DARK_THEME_WATER_DEEP,
    'fill-opacity': 0.92,
  },
};

const depthExtrusionLayer: LayerSpecification = {
  id: LAYER_IDS.WATER_DEPTH_EXTRUSION,
  type: 'fill-extrusion',
  source: SOURCE_IDS.DEPARE,
  'source-layer': 'DEPARE',
  minzoom: 0,
  layout: {
    visibility: 'none',
  },
  filter: createSafeDepthAreaFilter(10),
  paint: {
    'fill-extrusion-base': 0,
    'fill-extrusion-height': createDepthExtrusionHeightExpression(),
    'fill-extrusion-color': DARK_THEME_WATER_DEEP,
    'fill-extrusion-opacity': 0.45,
    'fill-extrusion-vertical-gradient': true,
  },
};

const hazardFillLayer: LayerSpecification = {
  id: LAYER_IDS.HAZARD_FILL,
  type: 'fill',
  source: SOURCE_IDS.DEPARE,
  'source-layer': 'DEPARE',
  minzoom: 0,
  filter: createDangerAreaFilter(10),
  paint: {
    'fill-color': DARK_THEME_HAZARD_FILL,
    'fill-opacity': 0.45,
  },
};

const depthContourLayer: LayerSpecification = {
  id: 'depth-contour',
  type: 'line',
  source: SOURCE_IDS.DEPCNT,
  'source-layer': 'DEPCNT',
  minzoom: 11,
  paint: {
    'line-color': '#4A90A4',
    'line-width': 0.9,
    'line-opacity': 0.58,
  },
};

const coastlineLayer: LayerSpecification = {
  id: 'coastline',
  type: 'line',
  source: SOURCE_IDS.COALNE,
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

const soundingLayer: LayerSpecification = {
  id: 'soundings',
  type: 'circle',
  source: SOURCE_IDS.SOUNDG,
  'source-layer': 'SOUNDG',
  minzoom: LAYER_ZOOM.DEPTH_POINTS_MIN,
  paint: {
    'circle-radius': 2.5,
    'circle-color': '#60A5FA',
    'circle-opacity': 0.72,
    'circle-stroke-width': 1,
    'circle-stroke-color': '#ffffff',
  },
};

const soundingLabelLayer: LayerSpecification = {
  id: 'sounding-labels',
  type: 'symbol',
  source: SOURCE_IDS.SOUNDG,
  'source-layer': 'SOUNDG',
  minzoom: LAYER_ZOOM.DEPTH_POINTS_MIN,
  layout: {
    'text-field': ['to-string', ['coalesce', ['get', 'depth'], ['get', 'DEPTH'], '']],
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

const obstructionSymbolLayer: LayerSpecification = {
  id: LAYER_IDS.OBSTRUCTION_SYMBOL,
  type: 'symbol',
  source: SOURCE_IDS.OBSTRN,
  'source-layer': 'OBSTRN',
  minzoom: 11,
  layout: {
    'icon-image': createObstructionIconExpression(),
    'icon-size': [
      'interpolate',
      ['linear'],
      ['zoom'],
      11, 0.45,
      14, 0.65,
      17, 0.9,
    ],
    'icon-allow-overlap': true,
    'icon-ignore-placement': true,
  },
};

const restrictedLayer: LayerSpecification = {
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

export const s57Layers: LayerSpecification[] = [
  waterDepthFlatLayer,
  depthExtrusionLayer,
  hazardFillLayer,
  landBaseLayer,
  land3DLayer,
  coastlineLayer,
  depthContourLayer,
  soundingLayer,
  soundingLabelLayer,
  obstructionSymbolLayer,
  restrictedLayer,
];

function buildSafeDepthGradient(isDarkMode: boolean): ExpressionSpecification {
  // Continuous depth gradient for areas above safety contour (drval1 >= threshold).
  // Anchors chosen so shallow-safe reads clearly blue, deep reads rich navy.
  return isDarkMode
    ? ['interpolate', ['linear'], createDepthValueExpression(),
        0,  DARK_THEME_WATER_SHALLOW,
        8,  DARK_THEME_WATER_SHALLOW,
        15, '#174070',
        30, '#0E2B52',
        60, DARK_THEME_WATER_DEEP,
      ]
    : ['interpolate', ['linear'], createDepthValueExpression(),
        0,  LIGHT_THEME_WATER_SHALLOW,
        8,  LIGHT_THEME_WATER_SHALLOW,
        15, '#4A90C0',
        30, '#3278AA',
        60, LIGHT_THEME_WATER_DEEP,
      ];
}

function buildWaterFlatColorExpression(isDarkMode: boolean): ExpressionSpecification {
  return buildSafeDepthGradient(isDarkMode);
}

function buildDepthExtrusionColorExpression(isDarkMode: boolean): ExpressionSpecification {
  return buildSafeDepthGradient(isDarkMode);
}

function getHazardFillColor(isDarkMode: boolean): string {
  return isDarkMode ? DARK_THEME_HAZARD_FILL : LIGHT_THEME_HAZARD_FILL;
}

function buildDepthExtrusionOpacityExpression(): number {
  return 0.45;
}

function setLayerVisibility(map: MapLibreMap, layerId: string, isVisible: boolean): void {
  if (!map.getLayer(layerId)) {
    return;
  }

  map.setLayoutProperty(layerId, 'visibility', isVisible ? 'visible' : 'none');
}

export function syncHydrologyLayerVisibility(map: MapLibreMap): void {
  const showExtrusions = map.getPitch() > EXTRUSION.HYDROLOGY_PITCH_THRESHOLD;

  setLayerVisibility(map, LAYER_IDS.WATER_DEPTH_FLAT, true);
  setLayerVisibility(map, LAYER_IDS.WATER_DEPTH_EXTRUSION, showExtrusions);
  setLayerVisibility(map, LAYER_IDS.HAZARD_FILL, true);
}

export function updateWaterDepthStyle(
  map: MapLibreMap,
  safetyContourVal: number,
  isDarkMode: boolean
): void {
  if (map.getLayer(LAYER_IDS.WATER_DEPTH_FLAT)) {
    map.setFilter(LAYER_IDS.WATER_DEPTH_FLAT, createSafeDepthAreaFilter(safetyContourVal));
    map.setPaintProperty(
      LAYER_IDS.WATER_DEPTH_FLAT,
      'fill-color',
      buildWaterFlatColorExpression(isDarkMode),
    );
  }

  if (map.getLayer(LAYER_IDS.WATER_DEPTH_EXTRUSION)) {
    map.setFilter(LAYER_IDS.WATER_DEPTH_EXTRUSION, createSafeDepthAreaFilter(safetyContourVal));
    map.setPaintProperty(
      LAYER_IDS.WATER_DEPTH_EXTRUSION,
      'fill-extrusion-color',
      buildDepthExtrusionColorExpression(isDarkMode),
    );
    map.setPaintProperty(
      LAYER_IDS.WATER_DEPTH_EXTRUSION,
      'fill-extrusion-opacity',
      buildDepthExtrusionOpacityExpression(),
    );
  }

  if (map.getLayer(LAYER_IDS.HAZARD_FILL)) {
    map.setFilter(LAYER_IDS.HAZARD_FILL, createDangerAreaFilter(safetyContourVal));
    map.setPaintProperty(
      LAYER_IDS.HAZARD_FILL,
      'fill-color',
      getHazardFillColor(isDarkMode),
    );
  }
}

export function applyMapTheme(
  map: MapLibreMap,
  safetyContourVal: number,
  isDarkMode: boolean
): void {
  const theme = isDarkMode ? 'dark' : 'light';

  MAP_THEME_PAINTS[theme].forEach(({ layerId, paintProperty, value }) => {
    if (map.getLayer(layerId)) {
      map.setPaintProperty(layerId, paintProperty, value);
    }
  });

  map.setLight(MAP_THEME_LIGHTS[theme]);
  updateWaterDepthStyle(map, safetyContourVal, isDarkMode);
  syncHydrologyLayerVisibility(map);
}

function withAlpha(hexColor: string, alpha: number): string {
  const normalized = hexColor.replace('#', '');
  const value = normalized.length === 3
    ? normalized.split('').map((char) => `${char}${char}`).join('')
    : normalized;
  const alphaHex = Math.round(alpha * 255).toString(16).padStart(2, '0');

  return `#${value}${alphaHex}`;
}

function drawObstructionGlyph(
  ctx: CanvasRenderingContext2D,
  iconId: string,
  centerX: number,
  centerY: number,
): void {
  ctx.save();
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.strokeStyle = '#F8FAFC';
  ctx.fillStyle = '#F8FAFC';
  ctx.lineWidth = 5;

  switch (iconId) {
    case OBSTRUCTION_ICON_IDS.wreck:
      ctx.beginPath();
      ctx.moveTo(centerX - 12, centerY - 8);
      ctx.lineTo(centerX + 12, centerY + 8);
      ctx.moveTo(centerX + 12, centerY - 8);
      ctx.lineTo(centerX - 12, centerY + 8);
      ctx.stroke();
      break;
    case OBSTRUCTION_ICON_IDS.rock:
      ctx.beginPath();
      ctx.moveTo(centerX, centerY - 13);
      ctx.lineTo(centerX + 12, centerY);
      ctx.lineTo(centerX, centerY + 13);
      ctx.lineTo(centerX - 12, centerY);
      ctx.closePath();
      ctx.fill();
      break;
    case OBSTRUCTION_ICON_IDS.pile:
      [-10, 0, 10].forEach((offset) => {
        ctx.beginPath();
        ctx.moveTo(centerX + offset, centerY - 12);
        ctx.lineTo(centerX + offset, centerY + 12);
        ctx.stroke();
      });
      break;
    default:
      ctx.font = 'bold 24px sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('?', centerX, centerY + 1);
      break;
  }

  ctx.restore();
}

function createObstructionIcon(iconId: string): ImageData | null {
  if (typeof document === 'undefined') {
    return null;
  }

  const canvas = document.createElement('canvas');
  canvas.width = 64;
  canvas.height = 64;

  const ctx = canvas.getContext('2d');
  if (!ctx) {
    return null;
  }

  ctx.clearRect(0, 0, canvas.width, canvas.height);

  ctx.beginPath();
  ctx.arc(32, 32, 25, 0, Math.PI * 2);
  ctx.fillStyle = withAlpha('#7F1D1D', 0.22);
  ctx.fill();
  ctx.lineWidth = 3.5;
  ctx.strokeStyle = withAlpha('#F87171', 0.95);
  ctx.stroke();

  ctx.beginPath();
  ctx.arc(32, 32, 18, 0, Math.PI * 2);
  ctx.fillStyle = withAlpha('#0F172A', 0.78);
  ctx.fill();

  drawObstructionGlyph(ctx, iconId, 32, 32);

  return ctx.getImageData(0, 0, canvas.width, canvas.height);
}

export function ensureObstructionIcons(map: MapLibreMap): void {
  Object.values(OBSTRUCTION_ICON_IDS).forEach((iconId) => {
    if (map.hasImage(iconId)) {
      return;
    }

    const imageData = createObstructionIcon(iconId);
    if (imageData) {
      map.addImage(iconId, imageData, { pixelRatio: 2 });
    }
  });
}

export function addS57Sources(map: MapLibreMap, safetyContourVal: number): void {
  Object.entries(createS57Sources(safetyContourVal)).forEach(([sourceId, sourceSpec]) => {
    if (!map.getSource(sourceId)) {
      map.addSource(sourceId, sourceSpec);
    }
  });
}

export function addS57Layers(map: MapLibreMap): void {
  s57Layers.forEach((layer) => {
    if (map.getLayer(layer.id)) {
      return;
    }

    const sourceId = 'source' in layer && typeof layer.source === 'string' ? layer.source : null;
    if (sourceId && !map.getSource(sourceId)) {
      return;
    }

    map.addLayer(layer);
  });
}

export function removeS57Sources(map: MapLibreMap): void {
  [...s57Layers].reverse().forEach((layer) => {
    if (map.getLayer(layer.id)) {
      map.removeLayer(layer.id);
    }
  });

  Object.keys(createS57Sources(10)).forEach((sourceId) => {
    if (map.getSource(sourceId)) {
      map.removeSource(sourceId);
    }
  });
}
