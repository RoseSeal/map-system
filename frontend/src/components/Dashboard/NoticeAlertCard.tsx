import type { NoticeAlertPayload } from '../../types/schema';
import { atonActionLabels, evidenceFieldLabels, formatEta } from '../../utils/navigationDisplay';
import { selectActiveNoticeAlerts, useNavigationStore } from '../../store/useNavigationStore';

// 与风险等级色区分的航标通告强调色
const NOTICE_COLOR = 'oklch(0.66 0.13 265)';

function formatClock(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleTimeString('zh-CN', { hour12: false });
}

function NoticeAlertItem({ alert, isDarkMode }: { alert: NoticeAlertPayload; isDarkMode: boolean }) {
  const { aton, notice } = alert;
  return (
    <div
      className="overflow-hidden rounded-2xl"
      data-testid="notice-alert-card"
      style={{
        background: isDarkMode ? 'rgba(255,255,255,0.04)' : 'rgba(255,255,255,0.6)',
        border: `0.5px solid color-mix(in oklch, ${NOTICE_COLOR} 35%, transparent)`,
        boxShadow: `0 4px 16px -6px color-mix(in oklch, ${NOTICE_COLOR} 22%, transparent)`,
      }}
    >
      <div
        className="flex items-center justify-between px-4 py-2.5"
        style={{ borderBottom: `0.5px solid color-mix(in oklch, ${NOTICE_COLOR} 18%, transparent)` }}
      >
        <div className="flex items-center gap-2">
          <span
            className="rounded-full px-1.5 py-0.5 text-[9px] font-semibold"
            style={{ background: `color-mix(in oklch, ${NOTICE_COLOR} 15%, transparent)`, color: NOTICE_COLOR }}
          >
            航标通告
          </span>
          <span className="text-[11px] font-semibold" style={{ color: 'var(--ink-900)' }}>
            {aton.waterway} {aton.name}
            {aton.is_virtual ? '（虚拟）' : ''}
          </span>
        </div>
        <span className="text-[9px]" style={{ color: 'var(--ink-500)' }}>
          {atonActionLabels[aton.last_action] ?? aton.last_action}
        </span>
      </div>

      <div className="space-y-2 px-4 py-3">
        <p className="text-[12.5px] leading-relaxed" style={{ color: 'var(--ink-700)' }}>
          计划航线前方 {Math.round(alert.distance_along_route_m)} m 进入该航标 {alert.buffer_m} m 范围，
          <span className="font-semibold" style={{ color: NOTICE_COLOR }}>{formatEta(alert.eta_seconds)}</span>
          ；航标距航线 {Math.round(alert.lateral_offset_m)} m。
        </p>

        {notice && (
          <p className="text-[11px]" style={{ color: 'var(--ink-700)' }}>
            依据：
            <a href={notice.url} target="_blank" rel="noreferrer" className="underline decoration-dotted">
              {notice.title}
            </a>
            {notice.notice_no ? `（${notice.notice_no}）` : ''} 第 {notice.item_seq} 条，{notice.published_at} 发布
          </p>
        )}

        <ul className="space-y-0.5">
          {Object.entries(alert.evidence).map(([field, excerpt]) => (
            <li key={field} className="text-[10px] leading-relaxed" style={{ color: 'var(--ink-500)' }}>
              · {evidenceFieldLabels[field] ?? field}：「{excerpt}」
            </li>
          ))}
        </ul>

        <div className="flex items-center justify-between pt-1">
          <span className="text-[9px]" style={{ color: 'var(--ink-500)' }}>
            计算于 {formatClock(alert.timestamp)} · 前瞻 {alert.lookahead_minutes} min
          </span>
          <span className="text-[9px]" style={{ color: 'var(--ink-500)' }}>
            通告数据截止 {alert.data_cutoff ?? '未知'}
          </span>
        </div>
      </div>
    </div>
  );
}

export function NoticeAlertCard({ isDarkMode }: { isDarkMode: boolean }) {
  const alerts = useNavigationStore(selectActiveNoticeAlerts);
  const ordered = Object.values(alerts).sort(
    (a, b) => (a.eta_seconds ?? Number.MAX_SAFE_INTEGER) - (b.eta_seconds ?? Number.MAX_SAFE_INTEGER),
  );
  if (ordered.length === 0) {
    return null;
  }
  return (
    <section className="space-y-2" aria-label="航标通告提醒">
      {ordered.map((alert) => (
        <NoticeAlertItem key={alert.alert_id} alert={alert} isDarkMode={isDarkMode} />
      ))}
    </section>
  );
}
