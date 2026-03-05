import { useState } from 'react'
import LegItem from './LegItem'

const MOBILITY_EMOJI = {
  TRANSIT: '🚌', WALK: '🚶', BIKE: '🚲', KICKBOARD: '🛴'
}

export default function RouteCard({ route, selected, onClick }) {
  const [expanded, setExpanded] = useState(false)

  return (
    <div
      onClick={onClick}
      className={`p-4 rounded-xl border cursor-pointer transition
        ${selected ? 'border-blue-500 bg-blue-50' : 'border-gray-200 bg-white'}`}
    >
      {/* 헤더 */}
      <div className="flex justify-between items-start">
        <div className="flex flex-col gap-1">
          {route.recommended && (
            <span className="text-xs bg-blue-500 text-white px-2 py-0.5 rounded-full w-fit">
              ⭐ 추천
            </span>
          )}
          <span className="text-sm text-gray-500">
            {route.legs.map(l => MOBILITY_EMOJI[l.type] ?? '🔵').join(' → ')}
          </span>
        </div>
        <span className="text-xl font-bold text-gray-800">{route.totalMinutes}분</span>
      </div>

      {/* 절약 시간 */}
      {route.comparison?.savedMinutes > 0 && (
        <p className="text-xs text-green-600 mt-1">
          🔥 기존보다 {route.comparison.savedMinutes}분 빠름
        </p>
      )}

      {/* 비용 */}
      <p className="text-sm text-gray-500 mt-1">
        {route.totalCostWon?.toLocaleString()}원
      </p>

      {/* 상세 토글 */}
      <button
        onClick={e => { e.stopPropagation(); setExpanded(v => !v) }}
        className="text-xs text-blue-500 mt-2 hover:underline"
      >
        {expanded ? '접기 ▲' : '상세 보기 ▼'}
      </button>

      {expanded && (
        <div className="mt-2 border-t pt-2 space-y-1">
          {route.legs.map((leg, i) => (
            <LegItem key={i} leg={leg} />
          ))}
        </div>
      )}
    </div>
  )
}
