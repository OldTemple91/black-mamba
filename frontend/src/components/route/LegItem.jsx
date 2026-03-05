const LEG_CONFIG = {
  TRANSIT:   { emoji: '🚌', color: 'bg-blue-100 text-blue-700',   label: '버스/지하철' },
  WALK:      { emoji: '🚶', color: 'bg-gray-100 text-gray-600',   label: '도보' },
  BIKE:      { emoji: '🚲', color: 'bg-green-100 text-green-700', label: '자전거' },
  KICKBOARD: { emoji: '🛴', color: 'bg-orange-100 text-orange-700', label: '킥보드' },
}

export default function LegItem({ leg }) {
  const config = LEG_CONFIG[leg.type] ?? { emoji: '🔵', color: 'bg-gray-100 text-gray-600', label: leg.type }

  return (
    <div className="flex items-center gap-3 py-2">
      {/* 아이콘 */}
      <div className={`w-8 h-8 rounded-full flex items-center justify-center text-sm flex-shrink-0 ${config.color}`}>
        {config.emoji}
      </div>

      {/* 내용 */}
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-gray-800">
            {leg.mode || config.label}
          </span>
          <span className="text-xs text-gray-400">{leg.durationMinutes}분</span>
        </div>
        {leg.start?.name && leg.end?.name && (
          <p className="text-xs text-gray-500 truncate">
            {leg.start.name} → {leg.end.name}
          </p>
        )}
      </div>

      {/* 거리 */}
      {leg.distanceMeters > 0 && (
        <span className="text-xs text-gray-400 flex-shrink-0">
          {leg.distanceMeters >= 1000
            ? `${(leg.distanceMeters / 1000).toFixed(1)}km`
            : `${leg.distanceMeters}m`}
        </span>
      )}
    </div>
  )
}
