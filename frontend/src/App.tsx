/**
 * Unmanned Fleet Risk Warning System
 * Phase 2: 2.5D Dynamic Risk Awareness Dashboard
 */

import { useEffect } from 'react';
import { MapContainer, StatusPanel, TargetsPanel, CompassOverlay } from './components';
import { socketService, startMockDataGenerator, stopMockDataGenerator } from './services';

// 主入口组件，负责整体布局和WebSocket连接管理
function App() {
  // start websocket connection
  // 组件挂载（打开页面）后建立 WebSocket 连接，用于接收实时目标/风险数据
  useEffect(() => {
    
    socketService.connect();
    
    return () => {
      // 组件卸载（关闭页面）时断开 WebSocket 连接，避免重复连接或内存泄漏
      socketService.disconnect();
    };
  }, []);
  
  // UI总布局：全屏地图 + 四个角的UI覆盖层（状态面板、指南针、目标列表、图例）
  return (
    <div className="relative w-screen h-screen overflow-hidden bg-gray-900">
      {/* Map (full screen) */}
      <MapContainer />
      
      {/* UI Overlays */}
      <div className="absolute inset-0 pointer-events-none z-50">
        {/* Top-left: Status Panel */}
        <div className="absolute top-4 left-4 pointer-events-auto">
          <StatusPanel />
        </div>
        
        {/* Top-right: Compass */}
        <div className="absolute top-4 right-16 pointer-events-auto">
          <CompassOverlay />
        </div>
        
        {/* Bottom-left: Targets Panel */}
        <div className="absolute bottom-4 left-4 pointer-events-auto">
          <TargetsPanel />
        </div>
        
        {/* Bottom-right: Legend */}
        <div className="absolute bottom-4 right-4 pointer-events-auto">
          <Legend />
        </div>
      </div>
    </div>
  );
}

// 静态无业务UI组件，显示风险等级图例
function Legend() {
  const items = [
    { color: '#10B981', label: 'SAFE' },
    { color: '#F59E0B', label: 'CAUTION' },
    { color: '#F97316', label: 'WARNING' },
    { color: '#EF4444', label: 'ALARM' },
  ];
  
  return (
    <div className="bg-gray-900/80 backdrop-blur-sm rounded-lg p-3 text-white text-xs">
      <div className="text-gray-400 uppercase tracking-wider mb-2">Risk Level</div>
      <div className="space-y-1">
        {items.map(item => (
          <div key={item.label} className="flex items-center gap-2">
            <span 
              className="w-3 h-3 rounded-sm"
              style={{ backgroundColor: item.color }}
            />
            <span>{item.label}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

export default App;
