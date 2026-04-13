/**
 * Schema V2 type definitions.
 * Authority: docs/EVENT_SCHEMA.md
 */

// ============================================================
// Shared utility types
// ============================================================

export type LonLat = [number, number];
export type RGBColor = [number, number, number];
export type RGBAColor = [number, number, number, number];

// ============================================================
// Shared enums
// ============================================================

export type RiskLevel = 'SAFE' | 'CAUTION' | 'WARNING' | 'ALARM';
export type SpeechMode = 'direct' | 'preview';
export type ConnectionType = 'risk' | 'chat';
export type TrackingStatus = 'tracking';
export type PredictionType = 'linear';
export type SafetyDomainShape = 'ellipse';
export type PlatformHealthStatus = 'NORMAL' | 'DEGRADED' | 'NUC';
export type GovernanceMode = 'adaptive';
export type ChatProvider = 'gemini' | 'zhipu';
export type ExplanationProvider = 'gemini' | 'zhipu' | 'fallback';

// ============================================================
// Risk payload nested types
// ============================================================

export interface Position {
  lon: number;
  lat: number;
}

export interface ShipDynamics {
  sog: number;
  cog: number;
  hdg: number;
  rot: number;
}

export interface PlatformHealth {
  status: PlatformHealthStatus;
  description: string;
}

export interface FutureTrajectory {
  prediction_type: PredictionType;
}

export interface SafetyDomainDimensions {
  fore_nm: number;
  aft_nm: number;
  port_nm: number;
  stbd_nm: number;
}

export interface SafetyDomain {
  shape_type: SafetyDomainShape;
  dimensions: SafetyDomainDimensions;
}

export interface Governance {
  mode: GovernanceMode;
  trust_factor: number;
}

export interface OwnShip {
  id: string;
  position: Position;
  dynamics: ShipDynamics;
  platform_health: PlatformHealth;
  future_trajectory: FutureTrajectory;
  safety_domain: SafetyDomain;
}

export interface CpaMetrics {
  dcpa_nm: number;
  tcpa_sec: number;
}

export interface GraphicCpaLine {
  own_pos: LonLat;
  target_pos: LonLat;
}

export interface OztSector {
  start_angle_deg: number;
  end_angle_deg: number;
  is_active: boolean;
}

export interface RiskAssessment {
  risk_level: RiskLevel;
  cpa_metrics: CpaMetrics;
  graphic_cpa_line?: GraphicCpaLine;
  ozt_sector?: OztSector;
  risk_score?: number;
  risk_confidence?: number;
}

export interface TargetVector {
  speed_kn: number;
  course_deg: number;
}

export interface RiskTarget {
  id: string;
  tracking_status: TrackingStatus;
  position: Position;
  vector: TargetVector;
  risk_assessment: RiskAssessment;
}

export interface EnvironmentContext {
  safety_contour_val: number;
  active_alerts: string[];
}

// ============================================================
// Risk SSE types
// ============================================================

export type RiskSseEventType = 'RISK_UPDATE' | 'EXPLANATION' | 'ERROR';

export interface RiskUpdatePayload {
  event_id: string;
  risk_object_id: string;
  timestamp: string;
  governance: Governance;
  own_ship: OwnShip;
  targets: RiskTarget[];
  environment_context: EnvironmentContext;
}

export interface ExplanationPayload {
  event_id: string;
  risk_object_id: string;
  target_id: string;
  risk_level: RiskLevel;
  provider: ExplanationProvider;
  text: string;
  timestamp: string;
}

export interface SseErrorPayload {
  event_id: string;
  connection: 'risk';
  error_code: string;
  error_message: string;
  reply_to_event_id: null;
  timestamp: string;
}

// ============================================================
// Chat WebSocket types
// ============================================================

export type ChatUplinkType = 'PING' | 'CHAT' | 'SPEECH' | 'CLEAR_HISTORY';
export type ChatDownlinkType = 'PONG' | 'CHAT_REPLY' | 'SPEECH_TRANSCRIPT' | 'ERROR' | 'CLEAR_HISTORY_ACK';

export interface ChatRequestPayload {
  conversation_id: string;
  event_id: string;
  content: string;
  selected_target_ids?: string[];
}

export interface SpeechRequestPayload {
  conversation_id: string;
  event_id: string;
  audio_data: string;
  audio_format: string;
  mode: SpeechMode;
  selected_target_ids?: string[];
}

export interface ClearHistoryPayload {
  conversation_id: string;
  event_id: string;
}

export interface ChatReplyPayload {
  event_id: string;
  conversation_id: string;
  reply_to_event_id: string;
  role: 'assistant';
  content: string;
  provider: ChatProvider;
  timestamp: string;
}

export interface SpeechTranscriptPayload {
  event_id: string;
  conversation_id: string;
  reply_to_event_id: string;
  transcript: string;
  language: string;
  timestamp: string;
}

export interface ChatErrorPayload {
  event_id: string;
  connection: 'chat';
  error_code: string;
  error_message: string;
  reply_to_event_id: string | null;
  timestamp: string;
}

export interface ClearHistoryAckPayload {
  event_id: string;
  conversation_id: string;
  reply_to_event_id: string;
  timestamp: string;
}

export interface ChatUplinkEnvelope {
  type: ChatUplinkType;
  source: 'client';
  payload: ChatRequestPayload | SpeechRequestPayload | ClearHistoryPayload | null;
}

export interface ChatDownlinkEnvelope {
  type: ChatDownlinkType;
  source: 'server';
  sequence_id: string;
  payload: ChatReplyPayload | SpeechTranscriptPayload | ChatErrorPayload | ClearHistoryAckPayload | null;
}
