import type { Layer, Position } from '@deck.gl/core';
import { PathLayer, ScatterplotLayer } from '@deck.gl/layers';
import type {
  AtonAction,
  AtonFeature,
  AtonFeatureCollection,
  NoticeAlertPayload,
  PlannedRoute,
  RGBAColor,
} from '../../types/schema';

// 航标与航线叠加层（v1.3 step-1）；图层 id 统一以 nav- 开头，供 MapContainer 的图层锚点识别。
const NAVIGATION_Z = 46;

const ACTION_COLORS: Record<AtonAction, RGBAColor> = {
  SET: [34, 197, 94, 235],
  ADJUST: [245, 158, 11, 235],
  RESTORE: [20, 184, 166, 235],
  REMOVE: [148, 163, 184, 200],
  MALFUNCTION: [148, 163, 184, 200],
};
const INACTIVE_COLOR: RGBAColor = [148, 163, 184, 200];
const ALERT_COLOR: RGBAColor = [129, 140, 248, 255];

export interface NavigationLayerInput {
  route: PlannedRoute | null;
  atons: AtonFeatureCollection | null;
  activeAlerts: Record<string, NoticeAlertPayload>;
  selectedAtonId: string | null;
  isDarkMode: boolean;
  onSelectAton: (atonId: string | null) => void;
}

export function atonFillColor(feature: AtonFeature): RGBAColor {
  if (feature.properties.state !== 'VALID') {
    return [0, 0, 0, 0];
  }
  return ACTION_COLORS[feature.properties.last_action] ?? ACTION_COLORS.SET;
}

export function buildNavigationLayers(input: NavigationLayerInput): Layer[] {
  const { route, atons, activeAlerts, selectedAtonId, isDarkMode, onSelectAton } = input;
  const layers: Layer[] = [];

  if (route) {
    layers.push(new PathLayer<{ path: Position[] }>({
      id: 'nav-route',
      data: [{ path: route.waypoints.map((w) => [w.lon, w.lat, NAVIGATION_Z] as Position) }],
      getPath: (d) => d.path,
      getColor: isDarkMode ? [165, 180, 252, 200] : [79, 70, 229, 190],
      getWidth: 3,
      widthUnits: 'pixels',
      jointRounded: true,
      capRounded: true,
    }));
  }

  // 缓冲圈只画有活动提醒的航标，半径取自后端提醒载荷，前端不复制阈值。
  const alerts = Object.values(activeAlerts);
  if (alerts.length > 0) {
    layers.push(new ScatterplotLayer<NoticeAlertPayload>({
      id: 'nav-aton-buffer',
      data: alerts,
      getPosition: (a) => [a.aton.lon, a.aton.lat, NAVIGATION_Z],
      getRadius: (a) => a.buffer_m,
      radiusUnits: 'meters',
      filled: true,
      getFillColor: [ALERT_COLOR[0], ALERT_COLOR[1], ALERT_COLOR[2], 28],
      stroked: true,
      getLineColor: ALERT_COLOR,
      getLineWidth: 1.5,
      lineWidthUnits: 'pixels',
      pickable: false,
    }));
  }

  const features = atons?.features ?? [];
  if (features.length > 0) {
    layers.push(new ScatterplotLayer<AtonFeature>({
      id: 'nav-atons',
      data: features,
      getPosition: (f) => [f.geometry.coordinates[0], f.geometry.coordinates[1], NAVIGATION_Z],
      getRadius: (f) => (f.id === selectedAtonId ? 8 : 6),
      radiusUnits: 'pixels',
      filled: true,
      getFillColor: atonFillColor,
      stroked: true,
      getLineColor: (f) => (f.properties.state === 'VALID' ? [255, 255, 255, 230] : INACTIVE_COLOR),
      getLineWidth: (f) => (f.id === selectedAtonId ? 3 : 1.5),
      lineWidthUnits: 'pixels',
      pickable: true,
      onClick: ({ object }) => {
        onSelectAton(object ? object.id : null);
        return true;
      },
      updateTriggers: {
        getRadius: [selectedAtonId],
        getLineWidth: [selectedAtonId],
      },
    }));
  }

  return layers;
}
