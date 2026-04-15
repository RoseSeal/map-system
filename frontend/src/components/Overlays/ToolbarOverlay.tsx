/**
 * Toolbar Overlay Component
 * Provides global settings like theme toggle and voice broadcast switch
 */

import {
  useAiCenterStore,
  selectSpeechEnabled,
  selectSpeechSupported,
} from '../../store';
import { useThemeStore } from '../../store/useThemeStore';
import { speechService } from '../../services/speechService';

export function ToolbarOverlay() {
  const speechEnabled = useAiCenterStore(selectSpeechEnabled);
  const speechSupported = useAiCenterStore(selectSpeechSupported);
  const setSpeechEnabled = useAiCenterStore((state) => state.setSpeechEnabled);
  const setSpeechUnlocked = useAiCenterStore((state) => state.setSpeechUnlocked);

  const { isDarkMode, toggleTheme } = useThemeStore();

  const handleSpeechToggle = () => {
    if (!speechSupported) {
      return;
    }

    if (speechEnabled) {
      setSpeechEnabled(false);
      speechService.stop();
      return;
    }

    const unlocked = speechService.unlock();
    setSpeechUnlocked(unlocked);
    setSpeechEnabled(unlocked);
  };

  return (
    <div className="bg-white/90 dark:bg-slate-950/60 backdrop-blur-md rounded-md p-2 text-slate-800 dark:text-white border border-slate-200 dark:border-white/10 shadow-lg pointer-events-auto flex flex-col gap-2 transition-colors duration-300">
      {/* 系统主题切换 */}
      <div className="flex items-center justify-between gap-4 min-w-[200px]">
        <div>
          <div className="text-[10px] text-slate-500 dark:text-slate-400 font-bold uppercase tracking-wider">系统主题</div>
          <div className="text-[9px] text-slate-400 dark:text-slate-500">
            切换显示模式
          </div>
        </div>
        <button
          type="button"
          onClick={toggleTheme}
          className="px-2 py-0.5 rounded text-[10px] font-medium border transition-colors border-slate-300 dark:border-white/10 bg-slate-100 dark:bg-slate-900/70 text-slate-700 dark:text-slate-300 hover:border-slate-400 dark:hover:border-slate-500"
        >
          {isDarkMode ? '深色' : '亮色'}
        </button>
      </div>

      {/* 语音播报切换 */}
      <div className="flex items-center justify-between gap-4 min-w-[200px] pt-1.5 border-t border-slate-200 dark:border-white/10">
        <div>
          <div className="text-[10px] text-slate-500 dark:text-slate-400 font-bold uppercase tracking-wider">语音播报</div>
          <div className="text-[9px] text-slate-400 dark:text-slate-500">
            {speechSupported ? '自动播报评估' : '不支持'}
          </div>
        </div>
        <button
          type="button"
          onClick={handleSpeechToggle}
          disabled={!speechSupported}
          className={[
            'px-2 py-0.5 rounded text-[10px] font-medium border transition-colors',
            speechSupported
              ? speechEnabled
                ? 'border-cyan-600/40 dark:border-cyan-500/40 bg-cyan-50 dark:bg-cyan-500/15 text-cyan-700 dark:text-cyan-200 hover:bg-cyan-100 dark:hover:bg-cyan-500/25'
                : 'border-slate-300 dark:border-white/10 bg-slate-100 dark:bg-slate-900/70 text-slate-700 dark:text-slate-300 hover:border-slate-400 dark:hover:border-slate-500'
              : 'border-slate-200 dark:border-white/5 bg-slate-50 dark:bg-slate-900/40 text-slate-400 dark:text-slate-600 cursor-not-allowed',
          ].join(' ')}
        >
          {speechEnabled ? '开启' : '关闭'}
        </button>
      </div>
    </div>
  );
}
