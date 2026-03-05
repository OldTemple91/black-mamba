const MOBILITY_EMOJI = {
  TRANSIT: '🚌', WALK: '🚶', BIKE: '🚲', KICKBOARD: '🛴'
}

export default function RouteCard({ route, selected, onClick }) {
  return (
    <div
      onClick={onClick}
      className={`p-4 rounded-xl border cursor-pointer transition
        ${selected ? 'border-blue-500 bg-blue-50' : 'border-gray-200 bg-white'}`}
    >
      <div className="flex justify-between items-start">
        <div>
          {route.recommended && (
            <span className="text-xs bg-blue-500 text-white px-2 py-0.5 rounded-full mr-2">
              ⭐ 추천
            </span>
          )}
          <span className="text-sm text-gray-500">
            {route.legs.map(l => MOBILITY_EMOJI[l.type] ?? '🔵').join(' → ')}
          </span>
        </div>
        <span className="text-xl font-bold text-gray-800">{route.totalMinutes}분</span>
      </div>
      {route.comparison?.savedMinutes > 0 && (
        <p className="text-xs text-green-600 mt-1">
          🔥 기존보다 {route.comparison.savedMinutes}분 빠름
        </p>
      )}
      <p className="text-sm text-gray-500 mt-1">
        {route.totalCostWon?.toLocaleString()}원
      </p>
    </div>
  )
}
