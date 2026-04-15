import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  explanationForAlarmFixture,
  riskUpdateFixture,
  sseErrorFixture,
} from '../test/fixtures';
import { riskSseService } from './riskSseService';

class MockEventSource {
  static instances: MockEventSource[] = [];

  static reset(): void {
    MockEventSource.instances = [];
  }

  readonly listeners = new Map<string, Set<EventListener>>();
  readonly removedEventTypes: string[] = [];

  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;

  closed = false;
  url: string;

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: EventListener): void {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set());
    }
    this.listeners.get(type)?.add(listener);
  }

  removeEventListener(type: string, listener: EventListener): void {
    this.removedEventTypes.push(type);
    this.listeners.get(type)?.delete(listener);
  }

  close(): void {
    this.closed = true;
  }

  emit(type: string, payload: unknown): void {
    const event = new MessageEvent(type, { data: JSON.stringify(payload) });
    this.listeners.get(type)?.forEach((listener) => {
      listener(event);
    });
  }

  emitRaw(type: string, rawData: string): void {
    const event = new MessageEvent(type, { data: rawData });
    this.listeners.get(type)?.forEach((listener) => {
      listener(event);
    });
  }
}

describe('riskSseService', () => {
  beforeEach(() => {
    Object.defineProperty(globalThis, 'EventSource', {
      value: MockEventSource,
      writable: true,
      configurable: true,
    });
    MockEventSource.reset();
    riskSseService.disconnect();
  });

  afterEach(() => {
    riskSseService.disconnect();
    MockEventSource.reset();
  });

  it('creates only one EventSource connection when connect is called repeatedly', () => {
    riskSseService.connect('http://localhost:8080/api/v2/risk');
    riskSseService.connect('http://localhost:8080/api/v2/risk');

    expect(MockEventSource.instances).toHaveLength(1);
  });

  it('notifies connected status on open event', () => {
    const connectionSpy = vi.fn();
    const off = riskSseService.onConnectionStatusChange(connectionSpy);

    riskSseService.connect('http://localhost:8080/api/v2/risk');
    MockEventSource.instances[0].onopen?.(new Event('open'));

    expect(connectionSpy).toHaveBeenCalledWith(true, null);
    off();
  });

  it('parses and forwards RISK_UPDATE, EXPLANATION and ERROR events', () => {
    const riskUpdateSpy = vi.fn();
    const explanationSpy = vi.fn();
    const errorSpy = vi.fn();

    const offRisk = riskSseService.onRiskUpdate(riskUpdateSpy);
    const offExplanation = riskSseService.onExplanation(explanationSpy);
    const offError = riskSseService.onError(errorSpy);

    riskSseService.connect('http://localhost:8080/api/v2/risk');

    const source = MockEventSource.instances[0];
    source.emit('RISK_UPDATE', riskUpdateFixture);
    source.emit('EXPLANATION', explanationForAlarmFixture);
    source.emit('ERROR', sseErrorFixture);

    expect(riskUpdateSpy).toHaveBeenCalledWith(riskUpdateFixture);
    expect(explanationSpy).toHaveBeenCalledWith(explanationForAlarmFixture);
    expect(errorSpy).toHaveBeenCalledWith(sseErrorFixture);

    offRisk();
    offExplanation();
    offError();
  });

  it('ignores invalid JSON payloads', () => {
    const riskUpdateSpy = vi.fn();
    const offRisk = riskSseService.onRiskUpdate(riskUpdateSpy);

    riskSseService.connect('http://localhost:8080/api/v2/risk');
    MockEventSource.instances[0].emitRaw('RISK_UPDATE', 'not-json');

    expect(riskUpdateSpy).not.toHaveBeenCalled();
    offRisk();
  });

  it('disconnect removes listeners, closes connection and emits manual disconnect status', () => {
    const connectionSpy = vi.fn();
    const off = riskSseService.onConnectionStatusChange(connectionSpy);

    riskSseService.connect('http://localhost:8080/api/v2/risk');
    const source = MockEventSource.instances[0];

    riskSseService.disconnect();

    expect(source.closed).toBe(true);
    expect(source.removedEventTypes).toEqual(expect.arrayContaining(['RISK_UPDATE', 'EXPLANATION', 'ERROR']));
    expect(connectionSpy).toHaveBeenLastCalledWith(false, null);

    off();
  });

  it('emits abnormal disconnect status on error event', () => {
    const connectionSpy = vi.fn();
    const off = riskSseService.onConnectionStatusChange(connectionSpy);

    riskSseService.connect('http://localhost:8080/api/v2/risk');
    MockEventSource.instances[0].onerror?.(new Event('error'));

    expect(connectionSpy).toHaveBeenCalledWith(false, '风险态势连接中断');
    off();
  });
});
