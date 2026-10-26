import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { NoticeAlertCard } from './NoticeAlertCard';
import { useNavigationStore } from '../../store/useNavigationStore';
import { noticeAlertFixture } from '../../test/fixtures';

describe('NoticeAlertCard', () => {
  beforeEach(() => {
    useNavigationStore.setState({ activeAlerts: {}, recentAlerts: [] });
  });

  it('renders nothing without active alerts', () => {
    const { container } = render(<NoticeAlertCard isDarkMode={false} />);
    expect(container.firstChild).toBeNull();
  });

  it('shows aid, ETA, notice provenance, evidence and data cutoff', () => {
    useNavigationStore.setState({ activeAlerts: { [noticeAlertFixture.alert_id]: noticeAlertFixture } });

    render(<NoticeAlertCard isDarkMode={false} />);

    expect(screen.getByText('航标通告')).toBeTruthy();
    expect(screen.getByText(/汉口水道 武汉二桥#1红浮（虚拟）/)).toBeTruthy();
    expect(screen.getByText('约 3 分 30 秒后')).toBeTruthy();
    expect(screen.getByRole('link', { name: '长江武汉航道局关于航标优化调整的航道通告' }).getAttribute('href'))
      .toBe(noticeAlertFixture.notice?.url);
    expect(screen.getByText(/汉道通〔2026〕210号/)).toBeTruthy();
    expect(screen.getByText(/位置：30°36′21″.3N，114°19′22″.2E/)).toBeTruthy();
    expect(screen.getByText(/通告数据截止 2026-10-23/)).toBeTruthy();
  });

  it('orders multiple alerts by ETA', () => {
    const later = { ...noticeAlertFixture, alert_id: 'later', eta_seconds: 400, aton: { ...noticeAlertFixture.aton, name: '武汉二桥#2红浮' } };
    useNavigationStore.setState({ activeAlerts: { later, [noticeAlertFixture.alert_id]: noticeAlertFixture } });

    render(<NoticeAlertCard isDarkMode={false} />);

    const cards = screen.getAllByTestId('notice-alert-card');
    expect(cards[0].textContent).toContain('武汉二桥#1红浮');
    expect(cards[1].textContent).toContain('武汉二桥#2红浮');
  });
});
