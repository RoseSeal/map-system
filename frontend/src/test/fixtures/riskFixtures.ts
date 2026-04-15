import type {
  ExplanationPayload,
  RiskTarget,
  RiskUpdatePayload,
  SseErrorPayload,
} from '../../types/schema';

const baseTargets: RiskTarget[] = [
  {
    id: 'TGT-ALARM',
    tracking_status: 'tracking',
    position: { lon: -73.84, lat: 40.61 },
    vector: { speed_kn: 12.4, course_deg: 88 },
    risk_assessment: {
      risk_level: 'ALARM',
      cpa_metrics: { dcpa_nm: 0.12, tcpa_sec: 160 },
      encounter_type: 'CROSSING',
      risk_score: 0.92,
      risk_confidence: 0.87,
    },
  },
  {
    id: 'TGT-CAUTION',
    tracking_status: 'tracking',
    position: { lon: -73.82, lat: 40.62 },
    vector: { speed_kn: 8.1, course_deg: 43 },
    risk_assessment: {
      risk_level: 'CAUTION',
      cpa_metrics: { dcpa_nm: 0.78, tcpa_sec: 980 },
      encounter_type: 'OVERTAKING',
      risk_score: 0.39,
      risk_confidence: 0.72,
    },
  },
];

export const riskUpdateFixture: RiskUpdatePayload = {
  event_id: 'risk-event-1',
  risk_object_id: 'risk-object-1',
  timestamp: '2026-04-15T12:00:00.000Z',
  governance: {
    mode: 'adaptive',
    trust_factor: 0.35,
  },
  own_ship: {
    id: 'OWN-001',
    position: { lon: -73.835, lat: 40.615 },
    dynamics: { sog: 13.2, cog: 91.4, hdg: 90.8, rot: 0 },
    platform_health: { status: 'NORMAL', description: 'Operational' },
    future_trajectory: { prediction_type: 'linear' },
    safety_domain: {
      shape_type: 'ellipse',
      dimensions: { fore_nm: 0.4, aft_nm: 0.2, port_nm: 0.15, stbd_nm: 0.15 },
    },
  },
  targets: baseTargets,
  environment_context: {
    safety_contour_val: 15,
    active_alerts: ['traffic_density_high'],
  },
};

export const riskUpdateWithoutAlarmFixture: RiskUpdatePayload = {
  ...riskUpdateFixture,
  event_id: 'risk-event-2',
  risk_object_id: 'risk-object-2',
  timestamp: '2026-04-15T12:01:00.000Z',
  targets: [baseTargets[1]],
};

export const explanationForAlarmFixture: ExplanationPayload = {
  event_id: 'explanation-1',
  risk_object_id: 'risk-object-1',
  target_id: 'TGT-ALARM',
  risk_level: 'ALARM',
  provider: 'gemini',
  text: 'Alarm target closing quickly on starboard side.',
  timestamp: '2026-04-15T12:00:05.000Z',
};

export const explanationForCautionFixture: ExplanationPayload = {
  event_id: 'explanation-2',
  risk_object_id: 'risk-object-1',
  target_id: 'TGT-CAUTION',
  risk_level: 'CAUTION',
  provider: 'zhipu',
  text: 'Caution target projected to stay clear with margin.',
  timestamp: '2026-04-15T12:00:08.000Z',
};

export const explanationForMissingTargetFixture: ExplanationPayload = {
  event_id: 'explanation-missing',
  risk_object_id: 'risk-object-1',
  target_id: 'TGT-MISSING',
  risk_level: 'WARNING',
  provider: 'fallback',
  text: 'This explanation should be ignored by store.',
  timestamp: '2026-04-15T12:00:10.000Z',
};

export const sseErrorFixture: SseErrorPayload = {
  event_id: 'risk-error-1',
  connection: 'risk',
  error_code: 'RISK_STREAM_INTERRUPTED',
  error_message: 'Risk stream interrupted',
  reply_to_event_id: null,
  timestamp: '2026-04-15T12:00:30.000Z',
};
