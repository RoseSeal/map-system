/**
 * Unmanned Fleet Risk Warning System
 * Phase 2: 2.5D Dynamic Risk Awareness Dashboard
 */

import { useEffect } from 'react';
import { MapContainer, StatusPanel, TargetsPanel, CompassOverlay } from './components';
import { startMockDataGenerator, stopMockDataGenerator } from './services';

function App() {
  // Start mock data on mount (for development)
  useEffect(() => {
    // Use mock data generator in development
    // In production, use socketService.connect() instead
    startMockDataGenerator(1000); // 1Hz updates
    
    return () => {
      stopMockDataGenerator();
    };
  }, []);
  
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
