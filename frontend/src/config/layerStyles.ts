/**
 * MapLibre Layer Style Definitions





























































































































































































**备注**: 本次修改主要目标是**组会演示**，代码中标注了 `// TODO: 后续替换` 的位置需要在正式开发时处理。---3. 与 VTS 系统对接2. 自动避障规划1. 多船协同调度### 长期3. 历史轨迹查询2. 添加航行警告功能1. 完善碰撞避让算法### 中期 (1个月)3. 替换硬编码航点2. 实现路径规划 API 对接1. 接入真实 AIS 数据流### 短期 (1-2周)## 💡 后续开发建议---- ✅ 追踪面板仅显示 1.5 海里内的船舶- ✅ 陆地 3D 凸起效果- ✅ 奶油色细海岸线- ✅ 青色深度等值线- ✅ 水深渐变（深蓝到深色）- ✅ 5 艘目标船带橙色轨迹- ✅ 本船蓝色轨迹 + 航点标记- ✅ 船舶沿航点在水域航行，不再穿越陆地刷新浏览器后应看到：## 🎯 效果验证---```  └── track/MqttMessageListener.java    [小改] 条件注解  ├── config/MqttConfig.java            [小改] 条件注解  ├── s57/S57TileRepository.java        [中改] SQL 修复  ├── s57/S57Controller.java            [小改] 新端点后端:  └── src/components/Map/MapContainer.tsx [中改] 多 source + 渲染  ├── src/config/layerStyles.ts         [大改] MVT sources + 样式  ├── src/config/constants.ts           [小改] 视角参数  ├── src/utils/mockDataGenerator.ts    [大改] 航点逻辑 + 目标船前端:新增文件数: 1 (本文档)修改文件数: 8```## 📁 文件变更统计---| 船舶类型 | 全部用 Cargo 图标 | 根据 AIS 船型字段 || MQTT | 已禁用 (mqtt.enabled=false) | 启用并连接 Broker || 海图数据 | Jamaica Bay 测试数据 | 实际作业区域 ENC || CPA/TCPA | 基于模拟距离计算 | 真实船舶动态 + 算法 || 目标船数据 | `generateTargetShips()` 生成 5 艘 | AIS 数据订阅 || 本船轨迹 | 硬编码 5 个航点 | 实时 GPS 轨迹 || 本船航点 | 硬编码 WAYPOINTS 数组 | 路径规划 API ||------|----------|----------|| 位置 | 临时方案 | 生产方案 |以下是为了组会演示而采用的临时方案，**生产环境必须替换**：## 🚨 临时解决方案 (Demo Only)---- 需要定期更新海图数据- Jamaica Bay 数据仅供演示，实际部署需导入作业区域的 ENC 数据**⚠️ 后续注意**:- ✅ enc_m_covr: 3 features- ✅ enc_lndare: 262 features- ✅ enc_coalne: 872 features- ✅ enc_soundg: 66 features  - ✅ enc_depare: 817 features**执行结果**:#### `ChartParser/import_to_postgis.py`### 数据导入---**⚠️ 后续需修改**: 同上- ✅ 添加相同的条件注解避免 Bean 找不到**修改内容**:#### 4. `track/MqttMessageListener.java`- 确保 MQTT Broker 可连接- 生产环境需启用 MQTT: 在 application.properties 添加 `mqtt.enabled=true`**⚠️ 后续需修改**:- ✅ 添加 `@ConditionalOnProperty(name = "mqtt.enabled", ...)` **修改内容**:#### 3. `config/MqttConfig.java`**✅ 可保留**: SQL 生成逻辑正确- ✅ 各图层独立 MVT 生成- ✅ 修复字段名大小写问题 (`"DRVAL1"`, `"DRVAL2"`)**修改内容**:#### 2. `s57/S57TileRepository.java`**✅ 可保留**: API 设计合理- ✅ 添加单图层端点 `GET /tiles/{z}/{x}/{y}/{layer}.pbf`**修改内容**:#### 1. `s57/S57Controller.java`### 后端修改 (`java/ENC-Safety-Service/geointel-dashboard/`)---- 轨迹数据应来自后端 WebSocket- 船舶图标应根据船型动态选择**⚠️ 后续需改为真实数据**:- ✅ 航点标记显示- ✅ 轨迹淡出效果- ✅ 使用 `allTargets` 渲染所有目标船- ✅ 添加所有 5 个 MVT sources**修改内容**:#### 4. `components/Map/MapContainer.tsx`**✅ 可保留**: 样式配置基本可用于生产- ✅ 陆地 3D 凸起 + 垂直渐变- ✅ 深度等值线青色- ✅ 海岸线奶油色细线- ✅ 水深渐变 5 级 (#1E3A5F ~ #0A0F1A)- ✅ 拆分为 5 个独立 MVT source (解决复合瓦片问题)**修改内容**:#### 3. `config/layerStyles.ts`- 地图中心应根据实际作业区域动态设置**⚠️ 后续需改为真实数据**:- ✅ `EXTRUSION.LAND_HEIGHT`: 80 (陆地凸起高度)- ✅ 默认缩放 zoom: 12, pitch: 45- ✅ `DEFAULT_VIEW_STATE` 中心改为 Jamaica Bay (-73.835, 40.615)**修改内容**:#### 2. `config/constants.ts`- CPA/TCPA 计算需要真实的船舶动态数据- 本船轨迹硬编码航点 → 实时 GPS 轨迹- `generateTargetShips()` → 接入 AIS 数据订阅- `WAYPOINTS` 数组 → 接入路径规划 API**⚠️ 后续需改为真实数据**:- ✅ 分离了 `allTargets` 和 `targets` (地图显示 vs 面板显示)- ✅ 增加了邻近过滤 (1.5海里范围)- ✅ 目标船轨迹 (橙色, 1.5分钟, 淡出)- ✅ 本船轨迹使用硬编码航点 + 淡出效果- ✅ 添加了 5 艘目标船 (T-101 ~ T-105)- ✅ 船速从 10 节提升到 25 节- ✅ 实现了航点跟随逻辑 (waypoint-following)**修改内容**:#### 1. `utils/mockDataGenerator.ts`### 前端修改 (`FrontEnd/unmanned-fleet-ui/src/`)## 🔧 修改文件清单---| P2 | 海图图层颜色区分 | ✅ 已修复 || P1 | 2D → 2.5D 视觉增强 | ✅ 已完成 || P0 | 红色航线闪烁问题 | ✅ 已修复 || P0 | 船舶在陆地上行驶 | ✅ 已修复 ||--------|------|------|| 优先级 | 任务 | 状态 |## 📋 修复任务清单---**状态**: ✅ 完成**目标**: 组会前紧急修复海图可视化模块  **日期**: 2026年1月31日晚   * S-57 MVT layer styling for 2.5D maritime chart rendering
 */

import { COLORS, LAYER_IDS, LAYER_ZOOM, EXTRUSION, MVT_CONFIG } from './constants';
import type { LayerSpecification, SourceSpecification } from 'maplibre-gl';

// Base URL for tile endpoint
const TILE_BASE_URL = 'http://localhost:8081/api/s57/tiles/{z}/{x}/{y}';

/** Individual source for each layer (workaround for composite tile issues) */
export const s57Sources: Record<string, SourceSpecification> = {
  's57-depare': {
    type: 'vector',
    tiles: [`${TILE_BASE_URL}/DEPARE.pbf`],
    minzoom: 0,
    maxzoom: 14,
  },
  's57-lndare': {
    type: 'vector',
    tiles: [`${TILE_BASE_URL}/LNDARE.pbf`],
    minzoom: 0,
    maxzoom: 14,
  },
  's57-coalne': {
    type: 'vector',
    tiles: [`${TILE_BASE_URL}/COALNE.pbf`],
    minzoom: 0,
    maxzoom: 14,
  },
  's57-depcnt': {
    type: 'vector',
    tiles: [`${TILE_BASE_URL}/DEPCNT.pbf`],
    minzoom: 0,
    maxzoom: 14,
  },
  's57-soundg': {
    type: 'vector',
    tiles: [`${TILE_BASE_URL}/SOUNDG.pbf`],
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
    'fill-extrusion-color': '#2D3748',  // Slightly lighter for contrast
    'fill-extrusion-height': EXTRUSION.LAND_HEIGHT,
    'fill-extrusion-base': EXTRUSION.LAND_BASE,
    'fill-extrusion-opacity': EXTRUSION.LAND_OPACITY,
    'fill-extrusion-vertical-gradient': true,  // Add vertical gradient
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
      ['coalesce', ['get', 'drval1'], 50],  // lowercase for MVT
      0, '#1E3A5F',      // Very shallow - lighter blue
      5, '#1E3A8A',      // Shallow
      10, '#172554',     // Medium depth
      20, '#0F172A',     // Deep
      50, '#0A0F1A',     // Very deep - darker
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
    'line-color': '#4A90A4',  // Subtle teal, less blue
    'line-width': 0.8,
    'line-opacity': 0.4,  // More transparent
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
    'line-color': '#F5F0E6',  // Cream/off-white - clear but not harsh
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
    'text-field': ['get', 'depth'],  // lowercase for MVT
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
