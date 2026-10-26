import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { noticeAlertFixture } from '../test/fixtures';
import { riskSseService } from '../services/riskSseService';
import { useNavigationStore } from './useNavigationStore';

class MockEventSource {
  static instances: MockEventSource[] = [];
  static readonly CLOSED = 2;
  readyState = 0;
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  readonly listeners = new Map<string, Set<EventListener>>();

  constructor(public url: string) {
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: EventListener): void {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set());
    }
    this.listeners.get(type)?.add(listener);
  }

  removeEventListener(type: string, listener: EventListener): void {
    this.listeners.get(type)?.delete(listener);
  }

  close(): void {
    this.readyState = MockEventSource.CLOSED;
  }

  emit(type: string, payload: unknown): void {
    const event = new MessageEvent(type, { data: JSON.stringify(payload) });
    this.listeners.get(type)?.forEach((listener) => listener(event));
  }
}

describe('useNavigationStore notice alerts', () => {
  beforeEach(() => {
    useNavigationStore.setState({ activeAlerts: {}, recentAlerts: [] });
  });

  it('upserts ACTIVE alerts by alert_id so refreshes replace the card', () => {
    const { applyNoticeAlert } = useNavigationStore.getState();

    applyNoticeAlert(noticeAlertFixture);
    applyNoticeAlert({ ...noticeAlertFixture, event_id: 'refresh', eta_seconds: 90 });

    const { activeAlerts } = useNavigationStore.getState();
    expect(Object.keys(activeAlerts)).toEqual(['notice-alert-1']);
    expect(activeAlerts['notice-alert-1'].eta_seconds).toBe(90);
  });

  it('moves CLEARED alerts out of the active set into the bounded recent list', () => {
    const { applyNoticeAlert } = useNavigationStore.getState();
    applyNoticeAlert(noticeAlertFixture);

    for (let i = 0; i < 7; i += 1) {
      applyNoticeAlert({ ...noticeAlertFixture, alert_id: `cleared-${i}`, status: 'CLEARED', clear_reason: 'PASSED' });
    }
    applyNoticeAlert({ ...noticeAlertFixture, status: 'CLEARED', clear_reason: 'PASSED' });

    const { activeAlerts, recentAlerts } = useNavigationStore.getState();
    expect(activeAlerts).toEqual({});
    expect(recentAlerts).toHaveLength(5);
    expect(recentAlerts[0].alert_id).toBe('notice-alert-1');
  });
});

describe('useNavigationStore reconnect calibration', () => {
  beforeEach(() => {
    Object.defineProperty(globalThis, 'EventSource', { value: MockEventSource, writable: true, configurable: true });
    MockEventSource.instances = [];
    riskSseService.disconnect();
    useNavigationStore.setState({ activeAlerts: {}, recentAlerts: [] });
  });

  afterEach(() => {
    riskSseService.disconnect();
  });

  it('drops alerts whose CLEARED was missed while the stream was down', () => {
    riskSseService.connect('http://localhost:8080/api/v2/risk');
    const first = MockEventSource.instances[0];
    first.onopen?.(new Event('open'));
    first.emit('NOTICE_ALERT', noticeAlertFixture);
    expect(Object.keys(useNavigationStore.getState().activeAlerts)).toEqual(['notice-alert-1']);

    // 断线期间后端已清除该提醒；EventSource 自动重连成功后不会再收到 CLEARED
    first.onopen?.(new Event('open'));
    expect(useNavigationStore.getState().activeAlerts).toEqual({});

    // 下一次接近产生新 ID，只剩一张卡片
    first.emit('NOTICE_ALERT', { ...noticeAlertFixture, alert_id: 'notice-alert-2' });
    expect(Object.keys(useNavigationStore.getState().activeAlerts)).toEqual(['notice-alert-2']);
  });
});
