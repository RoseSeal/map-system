/**
 * Type Definitions for Unmanned Fleet Risk Warning System
 * Generated from: System Interface Specification v2.1
 *
 * This file defines the complete RiskObject schema used for
 * real-time WebSocket communication between backend and frontend.
 */

// ============================================================
// 2.1 Governance & Metadata
// ============================================================

/** System governance mode */
export type GovernanceMode = 'normative' | 'coercive' | 'adaptive';

/** GaaS audit data */
export interface Governance {
  /** System governance mode indicator */
  mode: GovernanceMode;
  /**
   * System trust factor (0.0 - 1.0)
   * Frontend should display "Low Confidence" warning if < 0.4
   */
  trust_factor: number;
}

// ============================================================
// 2.2 Own Ship Observer
// ============================================================

/** WGS84 position */
export interface Position {
  lat: number;
  lon: number;
}

/** Real-time kinematics data */
export interface ShipDynamics {
  /** Speed Over Ground in knots */
  sog: number;
  /** Course Over Ground in degrees */
  cog: number;
  /** Heading in degrees - drives 3D model rotation */
  hdg: number;
  /** Rate of Turn in deg/min - used for CHL calculation */
  rot: number;
}

/** Platform health status */
export type HealthStatus = 'NORMAL' | 'DEGRADED' | 'NUC';

/** Generalized health state interface */
export interface PlatformHealth {
  /** Health status enum */
  status: HealthStatus;
  /** Short description (e.g., "Propulsion Loss") */
  description: string;
}

/** Trajectory prediction type */
export type PredictionType = 'linear' | 'curved_headline';

/** Curved Headline (CHL) parameters */
export interface CurvedHeadline {
  /** Turn radius in nautical miles */
  turn_radius_nm: number;
  /** Projection time in minutes */
  projected_time_min: number;
}

/** Safety corridor parameters */
export interface SafetyCorridor {
  /** Cross Track Distance limit in nm */
  xtd_nm: number;
  /** Centerline coordinates [lon, lat][] */
  centerline: [number, number][];
}

/** Future trajectory visualization data */
export interface FutureTrajectory {
  /** Prediction mode */
  prediction_type: PredictionType;
  /** CHL parameters (when type is curved_headline) */
  curved_headline?: CurvedHeadline;
  /** Safety corridor (when type is linear with corridor) */
  safety_corridor?: SafetyCorridor;
}

/** Safety domain shape type */
export type DomainShapeType = 'ellipse' | 'polygon';

/** Ellipse dimensions for safety domain */
export interface EllipseDimensions {
  /** Forward distance in nm */
  fore_nm: number;
  /** Aft distance in nm */
  aft_nm: number;
  /** Port distance in nm */
  port_nm: number;
  /** Starboard distance in nm */
  stbd_nm: number;
}

/** Safety domain (collision avoidance bubble) */
export interface SafetyDomain {
  /** Shape type */
  shape_type: DomainShapeType;
  /** Ellipse parameters (when shape is ellipse) */
  dimensions?: EllipseDimensions;
  /** Polygon vertices as [x_offset, y_offset] in meters (when shape is polygon) */
  polygon_vertices?: [number, number][];
}

/** Own ship state */
export interface OwnShip {
  /** Canonical ship identifier */
  id: string;
  /** WGS84 position */
  position: Position;
  /** Real-time dynamics */
  dynamics: ShipDynamics;
  /** Platform health status */
  platform_health: PlatformHealth;
  /** Future trajectory prediction */
  future_trajectory: FutureTrajectory;
  /** Safety domain for collision avoidance */
  safety_domain: SafetyDomain;
}

// ============================================================
// 2.3 Targets & Risks
// ============================================================

/** Risk level enum - determines color coding */
export type RiskLevel = 'SAFE' | 'CAUTION' | 'WARNING' | 'ALARM';

/** CPA metrics */
export interface CPAMetrics {
  /** Distance at CPA in nautical miles */
  dcpa_nm: number;
  /** Time to CPA in seconds */
  tcpa_sec: number;
}

/** Graphic CPA line visualization data */
export interface GraphicCPALine {
  /** Own ship position at TCPA [lon, lat] */
  own_pos: [number, number];
  /** Target position at TCPA [lon, lat] */
  target_pos: [number, number];
}

/** Object Obstacle Zone (OZT) sector */
export interface OZTSector {
  /** Danger sector start angle in degrees */
  start_angle_deg: number;
  /** Danger sector end angle in degrees */
  end_angle_deg: number;
  /** Whether OZT is active */
  is_active?: boolean;
}

/** Human-readable explanation for target risk */
export interface RiskExplanation {
  /** Explanation source, e.g. llm/rule/hybrid */
  source?: string;
  /** Explanation text rendered in UI */
  text?: string;
}

/** Risk assessment for a target */
export interface RiskAssessment {
  /** Risk level - determines color (green/yellow/orange/red) */
  risk_level: RiskLevel;
  /** CPA metrics */
  cpa_metrics: CPAMetrics;
  /** CPA visualization line */
  graphic_cpa_line?: GraphicCPALine;
  /** OZT sector visualization */
  ozt_sector?: OZTSector;
  /** Human-readable explanation */
  explanation?: RiskExplanation;
}

/** Target vessel vector */
export interface TargetVector {
  /** Speed in knots */
  speed_kn: number;
  /** Course in degrees */
  course_deg: number;
}

/** Tracking status */
export type TrackingStatus = 'tracking' | 'lost' | 'acquired';

/** Target vessel */
export interface Target {
  /** Target MMSI */
  id: string;
  /** Tracking status */
  tracking_status?: TrackingStatus;
  /** WGS84 position */
  position: Position;
  /** Movement vector */
  vector: TargetVector;
  /** Risk assessment */
  risk_assessment: RiskAssessment;
}

// ============================================================
// 2.4 Simulation Layer
// ============================================================

/** Ghost ship for simulation */
export interface GhostShip {
  /** Reference to real vessel MMSI */
  ref_mmsi: string;
  /** Future timestamp this ghost represents */
  timestamp_future: string;
  /** Opacity (0.1 - 0.5), decays with time */
  opacity: number;
  /** Predicted position */
  position: Position;
}

/** Simulation layer state */
export interface SimulationLayer {
  /** Whether simulation overlay is active */
  is_active: boolean;
  /** List of ghost ships */
  ghost_ships: GhostShip[];
}

// ============================================================
// 2.5 Environment Context
// ============================================================

/** Environment context from S-57 adapter */
export interface EnvironmentContext {
  /** Safety contour depth value in meters */
  safety_contour_val: number;
  /** Active alerts list */
  active_alerts?: string[];
}

// ============================================================
// Core RiskObject
// ============================================================

/**
 * RiskObject - Core data unit for each render frame
 * Backend pushes at 1Hz (AIS) or 10Hz (interpolated)
 */
export interface RiskObject {
  /** Unique message ID for audit logging */
  risk_object_id: string;
  /** UTC timestamp (ISO8601) - used for dead reckoning interpolation */
  timestamp: string;
  /** Governance and audit data */
  governance: Governance;
  /** Own ship state */
  own_ship: OwnShip;
  /** Target vessels (tracked - nearby only, for panel display) */
  targets: Target[];
  /** All target vessels (for map rendering - TODO: Remove in refactor) */
  all_targets?: Target[];
  /** Simulation layer (optional) */
  simulation_layer?: SimulationLayer;
  /** Environment context */
  environment_context: EnvironmentContext;
}

// ============================================================
// Chat Protocol
// ============================================================

export type ChatInputType = 'TEXT' | 'SPEECH';
export type ChatRole = 'user' | 'assistant' | 'system';

export interface ChatRequestMessage {
  sequence_id: string;
  message_id: string;
  role: 'user';
  input_type?: ChatInputType;
  content: string;
}

export interface ChatRequestEnvelope {
  type: 'CHAT';
  message: ChatRequestMessage;
}

export interface ChatReplyPayload {
  sequence_id: string;
  message_id: string;
  reply_to_message_id: string;
  role: 'assistant';
  content: string;
  source?: string;
  timestamp?: string;
}

export interface ChatErrorPayload {
  sequence_id: string;
  message_id: string;
  reply_to_message_id?: string;
  error_code: string;
  error_message: string;
  timestamp?: string;
}

// ============================================================
// WebSocket Envelope
// ============================================================

/** WebSocket message types */
export type MessageType =
  | 'RISK_UPDATE'
  | 'SNAPSHOT'
  | 'ALERT'
  | 'PING'
  | 'PONG'
  | 'CHAT'
  | 'CHAT_REPLY'
  | 'CHAT_ERROR';

/** WebSocket message envelope */
export interface WebSocketMessage {
  /** Message type */
  type: MessageType;
  /** Sequence ID for ordering or chat session binding */
  sequence_id?: number | string;
  /** Payload for backend -> frontend messages */
  payload?: RiskObject | ChatReplyPayload | ChatErrorPayload;
  /** Message for frontend -> backend chat requests */
  message?: ChatRequestMessage;
}

// ============================================================
// Utility Types
// ============================================================

/** Coordinate as [longitude, latitude] tuple */
export type LonLat = [number, number];

/** RGB color array for Deck.gl */
export type RGBColor = [number, number, number];

/** RGBA color array for Deck.gl */
export type RGBAColor = [number, number, number, number];
