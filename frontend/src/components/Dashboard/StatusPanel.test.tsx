import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatusPanel } from './StatusPanel';
import {
  useRiskStore,
  useAiCenterStore,
  selectEnvironment,
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
  selectEnvironment: vi.fn(),
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
    expect(screen.getByText('STREAM')).toBeDefined();
    expect(screen.getByText('AI-WS')).toBeDefined();
    expect(screen.getByText('INITIALIZING TELEMETRY...')).toBeDefined();
    expect(screen.getByText('本船态势')).toBeDefined();
  });

  it('renders own ship data when available', () => {
    const mockData = {
      ownShip: {
        dynamics: { sog: 10, cog: 180, hdg: 180 },
        position: { lat: 30, lon: 120 },
        platform_health: { status: 'NORMAL' }
      },
      environment: { active_alerts: ['LOW_VISIBILITY'] },
      governance: { trust_factor: 1.0 },
      riskConnectionState: 'connected',
      chatConnectionState: 'connected',
      isLowTrust: false
    };

    (useRiskStore as any).mockImplementation((selector: any) => {
      if (selector === selectOwnShip) return mockData.ownShip;
      if (selector === selectGovernance) return mockData.governance;
      if (selector === selectEnvironment) return mockData.environment;
      if (selector === selectRiskConnectionState) return mockData.riskConnectionState;
      if (selector === selectIsLowTrust) return mockData.isLowTrust;
      return null;
    });

    (useAiCenterStore as any).mockImplementation((selector: any) => {
      if (selector === selectChatConnectionState) return mockData.chatConnectionState;
      return null;
    });

    render(<StatusPanel />);
    expect(screen.getByText('本船态势')).toBeDefined();
    expect(screen.getByText('10.0')).toBeDefined();
    expect(screen.getByText('STREAM')).toBeDefined();
    expect(screen.getByText('AI-WS')).toBeDefined();
    expect(screen.getByText('环境告警 1 项')).toBeDefined();
  });

  it('renders low confidence warning when isLowTrust is true', () => {
    const mockData = {
      ownShip: {
        dynamics: { sog: 10, cog: 180, hdg: 180 },
        position: { lat: 30, lon: 120 },
        platform_health: { status: 'NORMAL' }
      },
      environment: { active_alerts: [] },
      governance: { trust_factor: 0.45 },
      riskConnectionState: 'connected',
      chatConnectionState: 'connected',
      isLowTrust: true
    };

    (useRiskStore as any).mockImplementation((selector: any) => {
      if (selector === selectOwnShip) return mockData.ownShip;
      if (selector === selectGovernance) return mockData.governance;
      if (selector === selectEnvironment) return mockData.environment;
      if (selector === selectRiskConnectionState) return mockData.riskConnectionState;
      if (selector === selectIsLowTrust) return mockData.isLowTrust;
      return null;
    });

    (useAiCenterStore as any).mockImplementation((selector: any) => {
      if (selector === selectChatConnectionState) return mockData.chatConnectionState;
      return null;
    });

    render(<StatusPanel />);
    expect(screen.getByText(/Trust Warning/)).toBeDefined();
    expect(screen.getByText('Trust Warning · 45%')).toBeDefined();
  });
});
