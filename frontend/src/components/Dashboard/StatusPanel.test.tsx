import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatusPanel } from './StatusPanel';
import {
  useRiskStore,
  useAiCenterStore,
  selectOwnShip,
  selectGovernance,
  selectRiskConnectionState,
  selectChatConnectionState,
  selectIsLowTrust,
} from '../../store';

// Mock store
vi.mock('../../store', () => ({
  useRiskStore: vi.fn(),
  useAiCenterStore: vi.fn(),
  selectOwnShip: vi.fn(),
  selectGovernance: vi.fn(),
  selectRiskConnectionState: vi.fn(),
  selectChatConnectionState: vi.fn(),
  selectIsLowTrust: vi.fn(),
}));

describe('StatusPanel', () => {
  it('renders waiting message when no data', () => {
    (useRiskStore as any).mockReturnValue(null);
    (useAiCenterStore as any).mockReturnValue('disconnected');
    
    render(<StatusPanel />);
    expect(screen.getByText('实时消息')).toBeDefined();
    expect(screen.getByText('AI 助手')).toBeDefined();
    expect(screen.getByText('等待数据初始化...')).toBeDefined();
  });

  it('renders own ship data when available', () => {
    const mockData = {
      ownShip: {
        dynamics: { sog: 10, cog: 180, hdg: 180 },
        position: { lat: 30, lon: 120 },
        platform_health: { status: 'NORMAL' }
      },
      governance: { trust_factor: 1.0 },
      riskConnectionState: 'connected',
      chatConnectionState: 'connected',
      isLowTrust: false
    };

    (useRiskStore as any).mockImplementation((selector: any) => {
      if (selector === selectOwnShip) return mockData.ownShip;
      if (selector === selectGovernance) return mockData.governance;
      if (selector === selectRiskConnectionState) return mockData.riskConnectionState;
      if (selector === selectIsLowTrust) return mockData.isLowTrust;
      return null;
    });

    (useAiCenterStore as any).mockImplementation((selector: any) => {
      if (selector === selectChatConnectionState) return mockData.chatConnectionState;
      return null;
    });

    render(<StatusPanel />);
    expect(screen.getByText('本船状态')).toBeDefined();
    expect(screen.getByText('10.0')).toBeDefined();
    expect(screen.getByText('实时消息')).toBeDefined();
    expect(screen.getByText('AI 助手')).toBeDefined();
  });

  it('renders low confidence warning when isLowTrust is true', () => {
    const mockData = {
      ownShip: {
        dynamics: { sog: 10, cog: 180, hdg: 180 },
        position: { lat: 30, lon: 120 },
        platform_health: { status: 'NORMAL' }
      },
      governance: { trust_factor: 0.45 },
      riskConnectionState: 'connected',
      chatConnectionState: 'connected',
      isLowTrust: true
    };

    (useRiskStore as any).mockImplementation((selector: any) => {
      if (selector === selectOwnShip) return mockData.ownShip;
      if (selector === selectGovernance) return mockData.governance;
      if (selector === selectRiskConnectionState) return mockData.riskConnectionState;
      if (selector === selectIsLowTrust) return mockData.isLowTrust;
      return null;
    });

    (useAiCenterStore as any).mockImplementation((selector: any) => {
      if (selector === selectChatConnectionState) return mockData.chatConnectionState;
      return null;
    });

    render(<StatusPanel />);
    expect(screen.getByText(/低置信度/)).toBeDefined();
    expect(screen.getByText(/45%/)).toBeDefined();
  });
});
