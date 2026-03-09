import { useState } from 'react'
import LegItem from './LegItem'

const MOBILITY_EMOJI = {
  TRANSIT: '🚌', WALK: '🚶', BIKE: '🚲', KICKBOARD: '🛴'
}

const ROUTE_TYPE_LABEL = {
  TRANSIT_ONLY:              '대중교통',
  TRANSIT_WITH_BIKE:         '대중교통 + 자전거',
  TRANSIT_WITH_KICKBOARD:    '대중교통 + 킥보드',
  MOBILITY_FIRST_TRANSIT:    '이동수단 → 대중교통',
  MOBILITY_TRANSIT_MOBILITY: '이동수단 + 대중교통 + 이동수단',
}

const MOBILITY_ONLY_LABEL = {
  BIKE:      '자전거로만',
  KICKBOARD: '킥보드로만',
}

function getMobilityOnlyLabel(legs) {
  const leg = legs.find(l => l.type === 'BIKE' || l.type === 'KICKBOARD')
  return (leg && MOBILITY_ONLY_LABEL[leg.type]) ?? '직접 이동'
}

export default function RouteCard({ route, selected, onClick }) {
  const [expanded, setExpanded] = useState(false)

  const routeLabel = route.type === 'MOBILITY_ONLY'
    ? getMobilityOnlyLabel(route.legs)
    : (ROUTE_TYPE_LABEL[route.type] ?? route.type)

  return (
    <div
      onClick={onClick}
      className={`p-4 rounded-xl border cursor-pointer transition
        ${selected ? 'border-blue-500 bg-blue-50' : 'border-gray-200 bg-white hover:border-gray-300'}`}
    >
      {/* 헤더 */}
      <div className="flex justify-between items-start">
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2 flex-wrap">
            {route.recommended && (
              <span className="text-xs bg-blue-500 text-white px-2 py-0.5 rounded-full">
                ⭐ 추천
              </span>
            )}
            <span className="text-xs text-gray-400">{routeLabel}</span>
          </div>
          {/* 이동수단 체인 */}
          <span className="text-sm text-gray-600">
            {route.legs.map(l => MOBILITY_EMOJI[l.type] ?? '🔵').join(' → ')}
          </span>
        </div>
        <div className="text-right">
          <span className="text-xl font-bold text-gray-800">{route.totalMinutes}분</span>
          {route.totalCostWon > 0 && (
            <p className="text-xs text-gray-400">{route.totalCostWon.toLocaleString()}원</p>
          )}
        </div>
      </div>

      {/* 절약 시간 */}
      {route.comparison?.savedMinutes > 0 && (
        <p className="text-xs text-green-600 mt-1">
          🔥 대중교통만 이용시보다 {route.comparison.savedMinutes}분 빠름
        </p>
      )}

      {/* 상세 토글 */}
      <button
        onClick={e => { e.stopPropagation(); setExpanded(v => !v) }}
        className="text-xs text-blue-500 mt-2 hover:underline"
      >
        {expanded ? '접기 ▲' : '상세 보기 ▼'}
      </button>

      {/* 상세 — 타임라인 스타일 */}
      {expanded && (
        <div className="mt-3 border-t pt-3">
          {route.legs.map((leg, i) => (
            <LegItem
              key={i}
              leg={leg}
              isLast={i === route.legs.length - 1}
            />
          ))}
        </div>
      )}
    </div>
  )
}
