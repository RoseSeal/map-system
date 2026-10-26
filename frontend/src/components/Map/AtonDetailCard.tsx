import type { AtonFeature, AtonState } from '../../types/schema';
import { useThemeStore } from '../../store/useThemeStore';
import {
  selectAtonCollection,
  selectSelectedAtonId,
  useNavigationStore,
} from '../../store/useNavigationStore';
import { atonActionLabels, evidenceFieldLabels } from '../../utils/navigationDisplay';

const stateLabels: Record<AtonState, string> = {
  VALID: '有效',
  POSITION_UNVERIFIED: '位置待复核',
  MALFUNCTION: '失常',
  REMOVED: '已撤除',
};

function formatTime(iso: string): string {
  return iso.replace('T', ' ').slice(0, 16);
}

export function AtonDetailCard() {
  const collection = useNavigationStore(selectAtonCollection);
  const selectedId = useNavigationStore(selectSelectedAtonId);
  const selectAton = useNavigationStore((state) => state.selectAton);
  const { isDarkMode } = useThemeStore();

  const feature: AtonFeature | undefined = collection?.features.find((f) => f.id === selectedId);
  if (!collection || !feature) {
    return null;
  }
  const { properties } = feature;
  const [lon, lat] = feature.geometry.coordinates;
  const noticesById = new Map(properties.notices.map((n) => [n.notice_id, n]));

  return (
    <div
      className="absolute bottom-6 left-1/2 z-40 w-[380px] -translate-x-1/2 rounded-2xl px-4 py-3 text-[11px] shadow-lg backdrop-blur"
      data-testid="aton-detail-card"
      style={{
        background: isDarkMode ? 'rgba(15,23,42,0.88)' : 'rgba(255,255,255,0.92)',
        color: 'var(--ink-700)',
      }}
    >
      <div className="mb-2 flex items-start justify-between gap-2">
        <div>
          <div className="text-[12.5px] font-semibold" style={{ color: 'var(--ink-900)' }}>
            {properties.waterway} {properties.name}
            {properties.is_virtual ? '（虚拟）' : ''}
          </div>
          <div className="text-[10px]" style={{ color: 'var(--ink-500)' }}>
            {stateLabels[properties.state]} · {atonActionLabels[properties.last_action] ?? properties.last_action}
            {' '}于 {formatTime(properties.effective_at)} · {lat.toFixed(6)}°N {lon.toFixed(6)}°E
          </div>
        </div>
        <button
          type="button"
          aria-label="关闭航标详情"
          className="text-[14px] leading-none"
          style={{ color: 'var(--ink-500)' }}
          onClick={() => selectAton(null)}
        >
          ×
        </button>
      </div>

      <ol className="max-h-48 space-y-2 overflow-y-auto">
        {properties.history.map((entry) => {
          const notice = noticesById.get(entry.notice_id);
          return (
            <li key={`${entry.notice_id}-${entry.item_seq}`}>
              <div className="font-medium">
                {formatTime(entry.effective_at)} {atonActionLabels[entry.action] ?? entry.action}
                {notice && (
                  <>
                    {' · '}
                    <a href={notice.url} target="_blank" rel="noreferrer" className="underline decoration-dotted">
                      {notice.notice_no ?? notice.title}
                    </a>
                    {' '}第 {entry.item_seq} 条
                  </>
                )}
              </div>
              <ul className="mt-0.5 space-y-0.5 text-[10px]" style={{ color: 'var(--ink-500)' }}>
                {Object.entries(entry.evidence).map(([field, excerpt]) => (
                  <li key={field}>· {evidenceFieldLabels[field] ?? field}：「{excerpt}」</li>
                ))}
              </ul>
            </li>
          );
        })}
      </ol>

      <div className="mt-2 border-t pt-1.5 text-[9px]" style={{ color: 'var(--ink-500)', borderColor: 'var(--ink-200, rgba(0,0,0,0.08))' }}>
        通告数据 {collection.coverage.published_from} 至 {collection.coverage.published_to}；
        {collection.assumptions.join('；')}
      </div>
    </div>
  );
}
