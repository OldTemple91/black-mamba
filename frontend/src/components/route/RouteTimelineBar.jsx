/**
 * 경로 전체를 이동수단별 비율대로 가로 막대로 시각화.
 * Citymapper / Google Maps 스타일의 Gantt-like 미니 타임라인.
 *
 * 예: 5분 도보 + 30분 지하철 2호선 + 6분 따릉이 = 41분
 *  →  ┃ 🚶5 ┃━━━━━━ 🚇 2호선 30 ━━━━━━━ ┃ 🚲 6 ┃
 */

// 서울 지하철 라인 공식 색상 (ODsay lineColor 보강용)
const SEOUL_SUBWAY_COLORS = {
  '1호선': '#0052A4',
  '2호선': '#00A84D',
  '3호선': '#EF7C1C',
  '4호선': '#00A5DE',
  '5호선': '#996CAC',
  '6호선': '#CD7C2F',
  '7호선': '#747F00',
  '8호선': '#E6186C',
  '9호선': '#BDB092',
  '경의중앙선': '#77C4A3',
  '공항철도': '#0090D2',
  '신분당선': '#D4003B',
  '경춘선': '#0C8E72',
  '수인분당선': '#F5A200',
  '우이신설선': '#B7C450',
}

function resolveColor(leg) {
  if (leg.type === 'WALK') return '#94a3b8'           // slate-400
  if (leg.type === 'BIKE') return '#10b981'           // emerald-500
  if (leg.type === 'KICKBOARD') return '#8b5cf6'      // violet-500

  // TRANSIT
  const lineName = leg.transitInfo?.lineName ?? ''
  const mapColor = SEOUL_SUBWAY_COLORS[lineName.trim()]
  if (mapColor) return mapColor

  // 백엔드에서 내려준 lineColor (ODsay 직접 제공)
  if (leg.transitInfo?.lineColor) return leg.transitInfo.lineColor

  if (leg.mode === 'BUS') return '#2563eb'            // blue-600
  return '#334155'                                     // slate-700
}

function resolveIcon(leg) {
  if (leg.type === 'WALK') return '🚶'
  if (leg.type === 'BIKE') return '🚲'
  if (leg.type === 'KICKBOARD') return '🛴'
  if (leg.mode === 'SUBWAY') return '🚇'
  if (leg.mode === 'BUS') return '🚌'
  return '🚏'
}

function resolveLabel(leg) {
  if (leg.type === 'WALK') return '도보'
  if (leg.type === 'BIKE') return '자전거'
  if (leg.type === 'KICKBOARD') return '킥보드'
  return leg.transitInfo?.lineName ?? '대중교통'
}

export default function RouteTimelineBar({ legs }) {
  const total = legs.reduce((s, l) => s + (l.durationMinutes || 0), 0)
  if (total <= 0) return null

  return (
    <div className="mt-3">
      <div className="flex h-7 w-full overflow-hidden rounded-lg shadow-sm ring-1 ring-slate-200
                      dark:ring-slate-700">
        {legs.map((leg, i) => {
          const minutes = leg.durationMinutes || 0
          const pct = (minutes / total) * 100
          if (pct <= 0) return null
          const color = resolveColor(leg)
          const icon = resolveIcon(leg)
          const label = resolveLabel(leg)
          // 너비 10% 미만이면 라벨 숨김
          const showLabel = pct >= 12
          return (
            <div
              key={i}
              className="flex h-full items-center justify-center px-1.5 text-[11px] font-semibold text-white/95
                         transition-all hover:brightness-110"
              style={{ width: `${pct}%`, backgroundColor: color }}
              title={`${label} · ${minutes}분`}
            >
              {showLabel ? (
                <span className="flex items-center gap-1 whitespace-nowrap">
                  <span>{icon}</span>
                  <span className="tabular-nums">{minutes}'</span>
                </span>
              ) : (
                <span className="text-[9px]">{icon}</span>
              )}
            </div>
          )
        })}
      </div>
      {/* 총 소요 요약 */}
      <div className="mt-1.5 flex items-center justify-between text-[10px] text-slate-500
                      dark:text-slate-400">
        <span className="font-medium">총 {total}분</span>
        <span className="tabular-nums">
          {legs.length}개 구간 · {total >= 60 ? `${Math.floor(total / 60)}시간 ${total % 60}분` : `${total}분`}
        </span>
      </div>
    </div>
  )
}
