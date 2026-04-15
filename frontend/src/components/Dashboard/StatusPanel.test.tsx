import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatusPanel } from './StatusPanel';
import { useRiskStore } from '../../store';

// Mock store
vi.mock('../../store', () => ({
  useRiskStore: vi.fn(),
  selectOwnShip: vi.fn(),
  selectGovernance: vi.fn(),
  selectIsConnected: vi.fn(),
  selectIsLowTrust: vi.fn(),
}));

describe('StatusPanel', () => {
  it('renders waiting message when no data', () => {
    (useRiskStore as any).mockReturnValue(null);
    
    render(<StatusPanel />);
    expect(screen.getByText('离线')).toBeDefined();
  });

  it('renders own ship data when available', () => {
    const mockData = {
      ownShip: {
        dynamics: { sog: 10, cog: 180, hdg: 180 },
        position: { lat: 30, lon: 120 },
        platform_health: { status: 'NORMAL' }
      },
      governance: { trust_factor: 1.0 },
      isConnected: true,
      isLowTrust: false
    };

    (useRiskStore as any).mockImplementation((selector: any) => {
        if (typeof selector === 'function') {
            // This is a simplification for the test
            if (selector.name === 'selectOwnShip') return mockData.ownShip;
            if (selector.name === 'selectGovernance') return mockData.governance;
            if (selector.name === 'selectIsConnected') return mockData.isConnected;
            if (selector.name === 'selectIsLowTrust') return mockData.isLowTrust;
        }
        return mockData.ownShip; // Default fallback for component's useRiskStore(selectOwnShip)
    });

    render(<StatusPanel />);
    expect(screen.getByText('本船状态')).toBeDefined();
    expect(screen.getByText('10.0')).toBeDefined();
  });
});
