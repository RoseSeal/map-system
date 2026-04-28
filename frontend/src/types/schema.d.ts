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
export type EncounterType = 'HEAD_ON' | 'OVERTAKING' | 'CROSSING' | 'UNDEFINED';
export type SpeechMode = 'direct' | 'preview';
export type ConnectionType = 'risk' | 'chat';
export type ChatAgentMode = 'CHAT' | 'AGENT';
export type AgentStepStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'FINALIZING';
export type OwnShipFollowMode = 'OFF' | 'SOFT' | 'LOCKED';
export type TrackingStatus = 'tracking' | 'stale';
export type PredictionType = 'linear' | 'cv';
export type SafetyDomainShape = 'ellipse';
export type PlatformHealthStatus = 'NORMAL' | 'DEGRADED' | 'NUC';
export type GovernanceMode = 'adaptive';
export type LlmProviderId = 'gemini' | 'zhipu';
export type LlmTaskType = 'explanation' | 'chat' | 'agent';
export type LlmQuotaStatus = 'UNKNOWN' | 'AVAILABLE' | 'LIMITED' | 'EXHAUSTED';
export type ChatProvider = LlmProviderId;
export type ExplanationProvider = LlmProviderId | 'fallback';
export type AdvisoryScope = 'SCENE';
export type AdvisoryStatus = 'ACTIVE' | 'SUPERSEDED';
export type AdvisoryActionType = 'COURSE_CHANGE' | 'SPEED_CHANGE' | 'MAINTAIN_COURSE' | 'MONITOR' | 'UNKNOWN';
export type AdvisoryUrgency = 'LOW' | 'MEDIUM' | 'HIGH' | 'IMMEDIATE';

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
  encounter_type?: EncounterType;
  risk_score?: number;
  risk_confidence?: number;
}

export interface TargetVector {
  speed_kn: number;
  course_deg: number;
}

export interface PredictedTrajectoryPoint {
  lat: number;
  lon: number;
  offset_seconds: number;
}

export interface PredictedTrajectory {
  prediction_type: string;
  horizon_seconds: number;
  points: PredictedTrajectoryPoint[];
}

export interface RiskTarget {
  id: string;
  tracking_status: TrackingStatus;
  position: Position;
  vector: TargetVector;
  predicted_trajectory?: PredictedTrajectory;
  risk_assessment: RiskAssessment;
}

export type WeatherCode = 'CLEAR' | 'FOG' | 'RAIN' | 'SNOW' | 'STORM';
export type EnvAlertCode =
  | 'LOW_VISIBILITY'
  | 'HIGH_WIND'
  | 'HEAVY_PRECIPITATION'
  | 'STRONG_CURRENT_SET'
  | 'SHOAL_PROXIMITY'
  | 'OBSTRUCTION_NEARBY'
  | 'DEPTH_DATA_MISSING';

export interface WeatherWind {
  speed_kn: number | null;
  direction_from_deg: number | null;
}

export interface WeatherSurfaceCurrent {
  speed_kn: number | null;
  set_deg: number | null;
}

export interface WeatherContext {
  weather_code: WeatherCode | null;
  visibility_nm: number | null;
  precipitation_mm_per_hr: number | null;
  wind: WeatherWind;
  surface_current: WeatherSurfaceCurrent;
  sea_state: number | null;
  source_zone_id?: string | null;
  updated_at: string;
}

export interface WeatherZoneContext {
  zone_id: string;
  weather_code: WeatherCode | null;
  visibility_nm: number | null;
  precipitation_mm_per_hr: number | null;
  wind: WeatherWind | null;
  surface_current: WeatherSurfaceCurrent | null;
  sea_state: number | null;
  updated_at: string | null;
  geometry: { type: 'Polygon' | 'MultiPolygon'; coordinates: unknown };
}

export interface NearestObstructionSummary {
  category: string | null;
  distance_nm: number | null;
  bearing_deg: number | null;
}

export interface HydrologyContext {
  own_ship_min_depth_m: number | null;
  nearest_shoal_nm: number | null;
  nearest_obstruction: NearestObstructionSummary | null;
}

export interface EnvironmentContext {
  safety_contour_val: number;
  active_alerts: EnvAlertCode[];
  weather?: WeatherContext | null;
  weather_zones?: WeatherZoneContext[] | null;
  hydrology?: HydrologyContext | null;
}

// ============================================================
// Risk SSE types
// ============================================================

export type RiskSseEventType = 'RISK_UPDATE' | 'EXPLANATION' | 'ADVISORY' | 'ERROR';

export interface RecommendedAction {
  type: AdvisoryActionType;
  description: string;
  urgency: AdvisoryUrgency;
}

export interface AdvisoryPayload {
  event_id: string;
  advisory_id: string;
  risk_object_id: string | null;
  snapshot_version: number;
  scope: AdvisoryScope;
  status: AdvisoryStatus;
  supersedes_id: string | null;
  valid_until: string;
  risk_level: RiskLevel;
  provider: string;
  timestamp: string;
  summary: string;
  affected_targets: string[];
  recommended_action: RecommendedAction;
  evidence_items: string[];
}

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

export type ExplanationLifecycleStatus = 'ACTIVE' | 'RESOLVED';
export type ExplanationResolutionReason = 'TARGET_SAFE' | 'TARGET_MISSING';

export interface StoredExplanation extends ExplanationPayload {
  status: ExplanationLifecycleStatus;
  resolved_at?: string;
  resolved_reason?: ExplanationResolutionReason;
  current_risk_level?: RiskLevel | null;
}

export interface ExplanationReference {
  target_id: string;
  explanation_event_id: string;
}

export interface LlmProviderCapability {
  provider: LlmProviderId;
  display_name: string;
  available: boolean;
  supported_tasks: LlmTaskType[];
  degraded_tasks?: LlmTaskType[];
  quota_status: LlmQuotaStatus;
  disabled_reason?: string;
}

export interface LlmProviderSelection {
  explanation_provider: LlmProviderId;
  chat_provider: LlmProviderId;
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

export type ChatUplinkType = 'PING' | 'CHAT' | 'SPEECH' | 'CLEAR_HISTORY' | 'CLEAR_EXPIRED_EXPLANATIONS' | 'SET_LLM_PROVIDER_SELECTION';
export type ChatDownlinkType = 'PONG' | 'CAPABILITY' | 'LLM_PROVIDER_SELECTION' | 'CHAT_REPLY' | 'AGENT_STEP' | 'SPEECH_TRANSCRIPT' | 'ERROR' | 'CLEAR_HISTORY_ACK' | 'EXPIRED_EXPLANATIONS_CLEARED';

export interface ChatRequestPayload {
  conversation_id: string;
  event_id: string;
  content: string;
  agent_mode?: ChatAgentMode;
  selected_target_ids?: string[];
  selected_explanation_refs?: ExplanationReference[];
  edit_last_user_message?: boolean;
}

export interface ClearExpiredExplanationsPayload {
  event_id: string;
}

export interface ExpiredExplanationsClearedPayload {
  event_id: string;
  reply_to_event_id: string;
  removed_event_ids: string[];
  cutoff_time: string;
  timestamp: string;
}

export interface SetLlmProviderSelectionPayload {
  event_id: string;
  explanation_provider?: LlmProviderId;
  chat_provider?: LlmProviderId;
}

export interface ChatCapabilityPayload {
  event_id: string;
  chat_available: boolean;
  agent_available: boolean;
  speech_transcription_available: boolean;
  disabled_reasons?: {
    chat?: string | null;
    agent?: string | null;
    speech_transcription?: string | null;
  };
  llm_providers: LlmProviderCapability[];
  effective_provider_selection: LlmProviderSelection;
  provider_selection_mutable: boolean;
  timestamp: string;
}

export interface LlmProviderSelectionPayload {
  event_id: string;
  reply_to_event_id: string;
  effective_provider_selection: LlmProviderSelection;
  timestamp: string;
}

export interface AgentStepPayload {
  event_id: string;
  conversation_id: string;
  reply_to_event_id: string;
  step_id: string;
  tool_name: string | null;
  status: AgentStepStatus;
  message: string;
  timestamp: string;
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
  payload: ChatRequestPayload | SpeechRequestPayload | ClearHistoryPayload | ClearExpiredExplanationsPayload | SetLlmProviderSelectionPayload | null;
}

export interface ChatDownlinkEnvelope {
  type: ChatDownlinkType;
  source: 'server';
  sequence_id: string;
  payload: ChatReplyPayload | SpeechTranscriptPayload | ChatErrorPayload | ClearHistoryAckPayload | ChatCapabilityPayload | LlmProviderSelectionPayload | AgentStepPayload | ExpiredExplanationsClearedPayload | null;
}
