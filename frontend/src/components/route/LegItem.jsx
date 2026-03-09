import { useState } from 'react'

const LEG_CONFIG = {
  TRANSIT:   { emoji: '🚌', dotColor: 'bg-blue-500',   textColor: 'text-blue-700',   bgLight: 'bg-blue-50'   },
  WALK:      { emoji: '🚶', dotColor: 'bg-gray-400',   textColor: 'text-gray-600',   bgLight: 'bg-gray-100'  },
  BIKE:      { emoji: '🚲', dotColor: 'bg-green-500',  textColor: 'text-green-700',  bgLight: 'bg-green-50'  },
  KICKBOARD: { emoji: '🛴', dotColor: 'bg-orange-500', textColor: 'text-orange-700', bgLight: 'bg-orange-50' },
}

/** 이동수단 메인 라벨 */
function getLegLabel(leg) {
  if (leg.type === 'TRANSIT') {
    const modeMap = { BUS: '버스', SUBWAY: '지하철', TRAIN: '기차', FERRY: '페리' }
    return modeMap[leg.mode] ?? '대중교통'
  }
  if (leg.type === 'KICKBOARD') {
    const op = leg.mobilityInfo?.operatorName
    if (!op || op === '개인') return '개인 킥보드'
    return '킥보드'
  }
  if (leg.type === 'BIKE') return '자전거'
  return '도보'
}

/** 거리 포맷 */
function fmtDist(meters) {
  if (!meters || meters <= 0) return null
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)}km` : `${meters}m`
}

export default function LegItem({ leg, isLast }) {
  const [showStops, setShowStops] = useState(false)

  const config = LEG_CONFIG[leg.type] ?? LEG_CONFIG.WALK
  const label  = getLegLabel(leg)
  const dist   = fmtDist(leg.distanceMeters)

  // TRANSIT 노선 배지 색상 (없으면 기본 파란색)
  const badgeColor = leg.transitInfo?.lineColor || '#3B82F6'

  // 경유 정류장 목록 (첫/마지막 역 제외한 중간 정류장)
  const allStops = leg.transitInfo?.passThroughStations ?? []
  // 첫/끝은 start.name / end.name으로 표시하므로 중간만 추출
  const midStops = allStops.length > 2 ? allStops.slice(1, -1) : []

  // 상세 태그
  const tags = []
  if (leg.transitInfo?.stationCount > 0)
    tags.push(`${leg.transitInfo.stationCount}정거장`)
  if (leg.mobilityInfo?.operatorName && leg.mobilityInfo.operatorName !== '개인')
    tags.push(leg.mobilityInfo.operatorName)
  if (leg.mobilityInfo?.batteryLevel != null && leg.mobilityInfo.batteryLevel < 100)
    tags.push(`🔋 ${leg.mobilityInfo.batteryLevel}%`)
  if (leg.mobilityInfo?.availableCount > 0)
    tags.push(`${leg.mobilityInfo.availableCount}대 이용 가능`)
  if (dist) tags.push(dist)

  return (
    <div className="flex gap-3">
      {/* 타임라인: 아이콘 + 연결선 */}
      <div className="flex flex-col items-center flex-shrink-0">
        <div className={`w-8 h-8 rounded-full flex items-center justify-center text-sm text-white ${config.dotColor}`}>
          {config.emoji}
        </div>
        {!isLast && <div className="w-0.5 flex-1 bg-gray-200 my-1 min-h-[16px]" />}
      </div>

      {/* 내용 */}
      <div className="flex-1 pb-3 min-w-0">
        {/* 헤더: 노선 배지 + 라벨 + 소요 시간 */}
        <div className="flex items-center gap-2 flex-wrap">
          {leg.transitInfo?.lineName && (
            <span
              className="text-xs px-2 py-0.5 rounded-full font-semibold text-white flex-shrink-0"
              style={{ backgroundColor: badgeColor }}
            >
              {leg.transitInfo.lineName}
            </span>
          )}
          <span className={`text-sm font-medium ${config.textColor}`}>{label}</span>
          <span className="text-xs text-gray-400 ml-auto flex-shrink-0">{leg.durationMinutes}분</span>
        </div>

        {/* 구간: 출발역 */}
        {leg.start?.name && (
          <div className="flex items-center gap-1 mt-1">
            <span className="w-1.5 h-1.5 rounded-full bg-gray-400 flex-shrink-0" />
            <span className="text-xs text-gray-600">{leg.start.name} 승차</span>
          </div>
        )}

        {/* 중간 정류장 토글 (TRANSIT이고 중간 정류장이 있을 때) */}
        {leg.type === 'TRANSIT' && midStops.length > 0 && (
          <div className="ml-3 mt-0.5">
            <button
              onClick={() => setShowStops(v => !v)}
              className="text-xs text-gray-400 hover:text-gray-600 flex items-center gap-1"
            >
              <span className="w-px h-3 bg-gray-300" />
              {showStops ? `▲ 정류장 숨기기` : `▼ ${midStops.length}개 정류장 경유`}
            </button>
            {showStops && (
              <div className="mt-1 ml-1 border-l-2 border-dashed border-gray-200 pl-2 space-y-0.5">
                {midStops.map((stop, i) => (
                  <div key={i} className="flex items-center gap-1">
                    <span className="w-1 h-1 rounded-full bg-gray-300 flex-shrink-0" />
                    <span className="text-xs text-gray-400">{stop}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* 구간: 도착역 */}
        {leg.end?.name && (
          <div className="flex items-center gap-1 mt-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-gray-500 flex-shrink-0" />
            <span className="text-xs text-gray-600">{leg.end.name} 하차</span>
          </div>
        )}

        {/* 이동수단 대여 위치 (BIKE/KICKBOARD) */}
        {(leg.type === 'BIKE' || leg.type === 'KICKBOARD') && leg.start?.name && (
          <p className="text-xs text-gray-500 mt-0.5">
            📍 {leg.start.name}에서 대여 → {leg.end?.name ?? '목적지'}
          </p>
        )}

        {/* 상세 태그 */}
        {tags.length > 0 && (
          <div className="flex flex-wrap gap-1 mt-1">
            {tags.map((tag, i) => (
              <span key={i} className={`text-xs px-1.5 py-0.5 rounded ${config.bgLight} ${config.textColor}`}>
                {tag}
              </span>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
