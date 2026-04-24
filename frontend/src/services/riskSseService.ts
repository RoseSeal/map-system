import type {
  AdvisoryPayload,
  ExplanationPayload,
  RiskUpdatePayload,
  SseErrorPayload,
} from '../types/schema';
import { BACKEND_CONFIG } from '../config/constants';
import type { DisplayConnectionState } from '../types/connection';

type RiskUpdateCallback = (payload: RiskUpdatePayload) => void;
type ExplanationCallback = (payload: ExplanationPayload) => void;
type AdvisoryCallback = (payload: AdvisoryPayload) => void;
type ErrorCallback = (payload: SseErrorPayload) => void;
type ConnectionStatusCallback = (state: DisplayConnectionState, error?: string | null) => void;

const DEFAULT_RISK_SSE_URL: string = BACKEND_CONFIG.RISK_SSE_URL;

class RiskSseService {
  private eventSource: EventSource | null = null;
  private riskUpdateCallbacks = new Set<RiskUpdateCallback>();
  private explanationCallbacks = new Set<ExplanationCallback>();
  private advisoryCallbacks = new Set<AdvisoryCallback>();
  private errorCallbacks = new Set<ErrorCallback>();
  private connectionStatusCallbacks = new Set<ConnectionStatusCallback>();
  private currentUrl = DEFAULT_RISK_SSE_URL;

  connect(url: string = this.currentUrl): void {
    if (this.eventSource) {
      return;
    }

    this.currentUrl = url;
    const eventSource = new EventSource(url);
    this.eventSource = eventSource;

    eventSource.onopen = () => {
      this.connectionStatusCallbacks.forEach((cb) => cb('connected', null));
    };
    eventSource.addEventListener('RISK_UPDATE', this.handleRiskUpdate as EventListener);
    eventSource.addEventListener('EXPLANATION', this.handleExplanation as EventListener);
    eventSource.addEventListener('ADVISORY', this.handleAdvisory as EventListener);
    eventSource.addEventListener('ERROR', this.handleErrorEvent as EventListener);
    eventSource.onerror = (event) => {
      console.error('[riskSseService] EventSource error', event);
      const connState: DisplayConnectionState = eventSource.readyState === EventSource.CLOSED
        ? 'disconnected'
        : 'reconnecting';
      const error = connState === 'disconnected' ? '风险态势连接中断' : null;
      this.connectionStatusCallbacks.forEach((cb) => cb(connState, error));
    };
  }

  disconnect(): void {
    if (!this.eventSource) {
      return;
    }

    this.eventSource.removeEventListener('RISK_UPDATE', this.handleRiskUpdate as EventListener);
    this.eventSource.removeEventListener('EXPLANATION', this.handleExplanation as EventListener);
    this.eventSource.removeEventListener('ADVISORY', this.handleAdvisory as EventListener);
    this.eventSource.removeEventListener('ERROR', this.handleErrorEvent as EventListener);
    this.eventSource.close();
    this.eventSource = null;
    this.connectionStatusCallbacks.forEach((cb) => cb('disconnected', null));
  }

  onRiskUpdate(cb: RiskUpdateCallback): () => void {
    this.riskUpdateCallbacks.add(cb);
    return () => {
      this.riskUpdateCallbacks.delete(cb);
    };
  }

  onExplanation(cb: ExplanationCallback): () => void {
    this.explanationCallbacks.add(cb);
    return () => {
      this.explanationCallbacks.delete(cb);
    };
  }

  onAdvisory(cb: AdvisoryCallback): () => void {
    this.advisoryCallbacks.add(cb);
    return () => {
      this.advisoryCallbacks.delete(cb);
    };
  }

  onError(cb: ErrorCallback): () => void {
    this.errorCallbacks.add(cb);
    return () => {
      this.errorCallbacks.delete(cb);
    };
  }

  onConnectionStatusChange(cb: ConnectionStatusCallback): () => void {
    this.connectionStatusCallbacks.add(cb);
    return () => {
      this.connectionStatusCallbacks.delete(cb);
    };
  }

  private handleRiskUpdate = (event: Event): void => {
    const payload = parseSsePayload<RiskUpdatePayload>(event);
    if (!payload) {
      return;
    }

    this.riskUpdateCallbacks.forEach((cb) => cb(payload));
  };

  private handleExplanation = (event: Event): void => {
    const payload = parseSsePayload<ExplanationPayload>(event);
    if (!payload) {
      return;
    }

    this.explanationCallbacks.forEach((cb) => cb(payload));
  };

  private handleAdvisory = (event: Event): void => {
    const payload = parseSsePayload<AdvisoryPayload>(event);
    if (!payload) {
      return;
    }

    this.advisoryCallbacks.forEach((cb) => cb(payload));
  };

  private handleErrorEvent = (event: Event): void => {
    const payload = parseSsePayload<SseErrorPayload>(event);
    if (!payload) {
      return;
    }

    this.errorCallbacks.forEach((cb) => cb(payload));
  };
}

function parseSsePayload<T>(event: Event): T | null {
  if (!(event instanceof MessageEvent) || typeof event.data !== 'string') {
    return null;
  }

  try {
    return JSON.parse(event.data) as T;
  } catch (error) {
    console.error('[riskSseService] Failed to parse SSE payload', error);
    return null;
  }
}

export const riskSseService = new RiskSseService();
