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
