import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface ThemeState {
  isDarkMode: boolean;
  toggleTheme: () => void;
  setTheme: (isDark: boolean) => void;
}

export const useThemeStore = create<ThemeState>()(
  persist(
    (set) => ({
      isDarkMode: true, // 系统默认深色优先
      toggleTheme: () =>
        set((state) => {
          const newIsDark = !state.isDarkMode;
          const html = document.documentElement;
          if (newIsDark) {
            html.classList.add('dark');
            html.classList.remove('light');
          } else {
            html.classList.remove('dark');
            html.classList.add('light');
          }
          return { isDarkMode: newIsDark };
        }),
      setTheme: (isDark) =>
        set(() => {
          const html = document.documentElement;
          if (isDark) {
            html.classList.add('dark');
            html.classList.remove('light');
          } else {
            html.classList.remove('dark');
            html.classList.add('light');
          }
          return { isDarkMode: isDark };
        }),
    }),
    {
      name: 'theme-storage',
    }
  )
);
