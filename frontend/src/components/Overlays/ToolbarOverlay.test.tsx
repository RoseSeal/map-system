import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ToolbarOverlay } from './ToolbarOverlay';
import { useAiCenterStore } from '../../store';
import { useThemeStore } from '../../store/useThemeStore';

// Mock stores and services
vi.mock('../../store', () => ({
  useAiCenterStore: vi.fn(),
  selectSpeechEnabled: vi.fn(),
  selectSpeechSupported: vi.fn(),
}));

vi.mock('../../store/useThemeStore', () => ({
  useThemeStore: vi.fn(),
}));

vi.mock('../../services/speechService', () => ({
  speechService: {
    stop: vi.fn(),
    unlock: vi.fn(() => true),
  },
}));

describe('ToolbarOverlay', () => {
  it('renders theme toggle and speech toggle', () => {
    (useThemeStore as any).mockReturnValue({
      isDarkMode: true,
      toggleTheme: vi.fn(),
    });
    (useAiCenterStore as any).mockImplementation((_selector: any) => {
      // Very basic mock to satisfy the component
      return true; 
    });

    render(<ToolbarOverlay />);
    
    expect(screen.getByText('系统主题')).toBeDefined();
    expect(screen.getByText('语音播报')).toBeDefined();
  });

  it('calls toggleTheme when theme button is clicked', () => {
    const toggleTheme = vi.fn();
    (useThemeStore as any).mockReturnValue({
      isDarkMode: true,
      toggleTheme,
    });
    
    render(<ToolbarOverlay />);
    const themeButton = screen.getByRole('button', { name: /深色|亮色/ });
    fireEvent.click(themeButton);
    
    expect(toggleTheme).toHaveBeenCalled();
  });
});
