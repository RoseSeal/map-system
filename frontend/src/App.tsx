/**
 * Unmanned Fleet Risk Warning System
 * Phase 2: 2.5D Dynamic Risk Awareness Dashboard
 */

import { useEffect } from 'react';
import { MapContainer, StatusPanel, TargetsPanel, CompassOverlay, RiskExplanationPanel } from './components';
import { socketService } from './services';

function App() {
  useEffect(() => {
    socketService.connect();

    return () => {
      socketService.disconnect();
    };
  }, []);

  return (
    <div className="relative w-screen h-screen overflow-hidden bg-gray-900">
      <MapContainer />

      <div className="absolute inset-0 pointer-events-none z-50">
        <div className="absolute top-4 left-4 pointer-events-auto">
          <StatusPanel />
        </div>

        <div className="absolute top-4 right-16 pointer-events-auto">
          <CompassOverlay />
        </div>

        <div className="absolute top-52 right-4 pointer-events-auto">
          <RiskExplanationPanel />
        </div>

        <div className="absolute bottom-4 left-4 pointer-events-auto">
          <TargetsPanel />
        </div>

        <div className="absolute bottom-4 right-4 pointer-events-auto">
          <Legend />
        </div>
      </div>
    </div>
  );
}

function Legend() {
  const items = [
    { color: '#10B981', label: '安全' },
    { color: '#F59E0B', label: '注意' },
    { color: '#F97316', label: '警告' },
    { color: '#EF4444', label: '警报' },
  ];

  return (
    <div className="bg-gray-900/80 backdrop-blur-sm rounded-lg p-3 text-white text-xs">
      <div className="text-gray-400 tracking-wider mb-2">风险等级</div>
      <div className="space-y-1">
        {items.map((item) => (
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
