import { useEffect } from 'react';
import {
  MapContainer,
  RiskExplanationPanel,
  StatusPanel,
  TargetsPanel,
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
        <div
          className="absolute flex flex-col gap-3 pointer-events-auto"
          style={{
            top: 16,
            bottom: 16,
            left: 16,
            width: 308,
          }}
        >
          <StatusPanel />
          <TargetsPanel />
        </div>

        <RiskExplanationPanel />
      </div>
    </div>
  );
}

export default App;
