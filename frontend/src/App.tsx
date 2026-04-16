/**
 * Unmanned Fleet Risk Warning System
 * Phase 2: 2.5D Dynamic Risk Awareness Dashboard
 */

import { useEffect } from 'react';
import { MapContainer, StatusPanel, TargetsPanel, RiskExplanationPanel } from './components';
import { chatWsService, riskSseService } from './services';
import { useAiSpeechBroadcast } from './hooks/useAiSpeechBroadcast';

function App() {
  useAiSpeechBroadcast();

  useEffect(() => {
    riskSseService.connect();
    chatWsService.connect();

    return () => {
      riskSseService.disconnect();
      chatWsService.disconnect();
    };
  }, []);

  return (
    <div className="relative w-screen h-screen overflow-hidden bg-slate-300 dark:bg-gray-900 transition-colors duration-300">
      <MapContainer />

      {/* 叠加层容器 */}
      <div className="absolute inset-0 pointer-events-none z-50">
        {/* 左侧态势监控信息簇 */}
        <div className="absolute top-4 left-4 flex flex-col gap-4 pointer-events-auto max-h-[calc(100vh-2rem)] overflow-y-auto scrollbar-hide">
          <StatusPanel />
          <TargetsPanel />
        </div>

        {/* 右侧 AI 工作区 (自包含布局与动画) */}
        <RiskExplanationPanel />

        {/* 右下角图例 */}
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
    <div className="bg-white/80 dark:bg-gray-900/80 backdrop-blur-sm rounded-lg p-3 text-slate-800 dark:text-white text-xs transition-colors duration-300">
      <div className="text-slate-500 dark:text-gray-400 tracking-wider mb-2">风险等级</div>
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
