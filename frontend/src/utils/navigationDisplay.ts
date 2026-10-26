import type { AtonAction } from '../types/schema';

export const atonActionLabels: Record<AtonAction, string> = {
  SET: '新设',
  ADJUST: '位置调整',
  REMOVE: '撤除',
  MALFUNCTION: '失常',
  RESTORE: '恢复',
};

export const evidenceFieldLabels: Record<string, string> = {
  action: '动作',
  effective_time: '时间',
  waterway: '水道',
  aton_name: '航标',
  is_virtual: '虚拟',
  position_raw: '坐标',
  position_text: '位置',
  malfunction_kind: '失常类型',
};

export function formatEta(seconds: number | null): string {
  if (seconds === null) {
    return '—';
  }
  if (seconds <= 0) {
    return '已进入范围';
  }
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return minutes > 0 ? `约 ${minutes} 分 ${rest} 秒后` : `约 ${rest} 秒后`;
}
