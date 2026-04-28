import { useEffect } from 'react';
import {
  MapContainer,
  MergedLeftPanel,
  RiskExplanationPanel,
} from './components';
import { useAiSpeechBroadcast } from './hooks/useAiSpeechBroadcast';
import { chatWsService, riskSseService } from './services';

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
    <div className="relative h-screen w-screen overflow-hidden bg-[#e7eef4] transition-colors duration-300 dark:bg-[#0b1220]">
      <MapContainer />

      <div className="absolute inset-0 z-50 pointer-events-none">
        <MergedLeftPanel />
        <RiskExplanationPanel />
      </div>
    </div>
  );
}

export default App;
