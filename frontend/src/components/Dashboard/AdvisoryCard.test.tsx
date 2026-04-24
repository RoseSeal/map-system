import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AdvisoryCard } from './AdvisoryCard';
import { useRiskStore, selectActiveAdvisory } from '../../store';
import { advisoryFixture } from '../../test/fixtures';

vi.mock('../../store', () => ({
  useRiskStore: vi.fn(),
  selectActiveAdvisory: vi.fn(),
}));

const mockExpireActiveAdvisory = vi.fn();

function setupMock(advisory: typeof advisoryFixture | null) {
  (useRiskStore as unknown as ReturnType<typeof vi.fn>).mockImplementation((selector: (state: unknown) => unknown) => {
    if (selector === selectActiveAdvisory) {
      return advisory;
    }
    return mockExpireActiveAdvisory;
  });
}

describe('AdvisoryCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing when there is no active advisory', () => {
    setupMock(null);
    const { container } = render(<AdvisoryCard isDarkMode={false} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders summary when advisory is active', () => {
    setupMock(advisoryFixture);
    render(<AdvisoryCard isDarkMode={false} />);
    expect(screen.getByText(advisoryFixture.summary)).toBeTruthy();
  });

  it('renders recommended action description', () => {
    setupMock(advisoryFixture);
    render(<AdvisoryCard isDarkMode={false} />);
    expect(screen.getByText(advisoryFixture.recommended_action.description)).toBeTruthy();
  });

  it('renders evidence items', () => {
    setupMock(advisoryFixture);
    render(<AdvisoryCard isDarkMode={false} />);
    advisoryFixture.evidence_items.forEach((item) => {
      expect(screen.getByText(`· ${item}`)).toBeTruthy();
    });
  });

  it('renders risk level badge', () => {
    setupMock(advisoryFixture);
    render(<AdvisoryCard isDarkMode={false} />);
    expect(screen.getByText('ALARM')).toBeTruthy();
  });
});
