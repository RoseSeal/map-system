import type { EncounterType } from '../types/schema';

/**
 * Translates encounter type to Chinese.
 */
export function translateEncounterType(type: EncounterType | string | undefined): string | undefined {
  if (!type || type === 'UNDEFINED') return undefined;
  switch (type) {
    case 'HEAD_ON': return '对遇';
    case 'OVERTAKING': return '追越';
    case 'CROSSING': return '交叉';
    default: return type;
  }
}

/**
 * Gets the border width for target card based on risk score.
 */
export function getRiskScoreBorderWidth(score: number | undefined): number {
  if (score === undefined) return 3;
  if (score >= 0.7) return 6;
  if (score >= 0.4) return 4;
  return 3;
}

/**
 * Gets the opacity for target card based on risk confidence.
 */
export function getRiskConfidenceOpacity(confidence: number | undefined): number | undefined {
  if (confidence === undefined || confidence >= 0.5) return undefined;
  if (confidence < 0.3) return 0.45;
  return 0.6;
}
