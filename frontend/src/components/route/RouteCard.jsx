import { useState } from 'react'
import LegItem from './LegItem'
import {
  getComparisonBars,
  getCostBreakdown,
  getDebugFacts,
  getHubSummary,
  getRecommendationReasons,
  getRiskBadges,
  getTransferSummary,
} from '../../utils/routeInsights'

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

function summarizeLeg(leg) {
  if (leg.type === 'TRANSIT') {
    if (leg.mode === 'SUBWAY') return `🚇 ${leg.transitInfo?.lineName ?? '지하철'}`
    if (leg.mode === 'BUS') return `🚌 ${leg.transitInfo?.lineName ?? '버스'}`
    return `🚌 ${leg.transitInfo?.lineName ?? '대중교통'}`
  }
  if (leg.type === 'BIKE') return '🚲 자전거'
  if (leg.type === 'KICKBOARD') return '🛴 킥보드'
  return '🚶 도보'
}

export default function RouteCard({
  route,
  selected,
  onClick,
  baselineRoute,
  comparisonContext,
  searchMode,
  showDebug,
}) {
  const [expanded, setExpanded] = useState(false)

  const routeLabel = route.type === 'MOBILITY_ONLY'
    ? getMobilityOnlyLabel(route.legs)
    : (ROUTE_TYPE_LABEL[route.type] ?? route.type)
  const reasons = getRecommendationReasons(route, baselineRoute)
  const risks = getRiskBadges(route)
  const transfers = getTransferSummary(route)
  const hubs = getHubSummary(route)
  const comparisonBars = comparisonContext ? getComparisonBars(route, comparisonContext) : []
  const costBreakdown = getCostBreakdown(route)
  const debugFacts = showDebug ? getDebugFacts(route, baselineRoute, searchMode) : []

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
            {risks.map(risk => (
              <span key={risk.label} className={`text-[11px] px-2 py-0.5 rounded-full ${risk.className}`}>
                {risk.label}
              </span>
            ))}
          </div>
          {/* 이동수단 체인 */}
          <span className="text-sm text-gray-600">
            {route.legs.map(summarizeLeg).join(' → ')}
          </span>
        </div>
        <div className="text-right">
          <span className="text-xl font-bold text-gray-800">{route.totalMinutes}분</span>
          {route.totalCostWon > 0 && (
            <>
              <p className="text-xs text-gray-400">{route.totalCostWon.toLocaleString()}원</p>
              {costBreakdown.length > 0 && (
                <p className="mt-1 text-[11px] text-gray-400 max-w-[180px] text-right">
                  {costBreakdown.map(item => `${item.label} ${item.amountWon.toLocaleString()}원`).join(' + ')}
                </p>
              )}
            </>
          )}
        </div>
      </div>

      {/* 절약 시간 */}
      {route.comparison?.savedMinutes > 0 && (
        <p className="text-xs text-green-600 mt-1">
          🔥 대중교통만 이용시보다 {route.comparison.savedMinutes}분 빠름
        </p>
      )}

      <div className="mt-3 flex flex-wrap gap-2">
        {reasons.map(reason => (
          <span key={reason} className="text-xs px-2 py-1 rounded-full bg-sky-50 text-sky-700 border border-sky-200">
            {reason}
          </span>
        ))}
      </div>

      {hubs.length > 0 && (
        <div className="mt-3 rounded-lg border border-violet-200 bg-violet-50 px-3 py-2">
          <p className="text-[11px] font-semibold text-violet-700">경로 허브</p>
          <div className="mt-2 flex flex-wrap gap-2">
            {hubs.map(hub => (
              <span key={`${hub.label}-${hub.detail}`} className="text-[11px] px-2 py-1 rounded-full border border-violet-200 bg-white text-violet-700">
                {hub.label} · {hub.detail}
                {hub.source === 'selected-candidate' && (
                  <> · {hub.metadata.selectionPhase === 'FIRST_MILE' ? '후보(출발)' : hub.metadata.selectionPhase === 'LAST_MILE' ? '후보(도착)' : '후보'}</>
                )}
              </span>
            ))}
          </div>
        </div>
      )}

      <div className="mt-3 grid grid-cols-2 gap-2">
        {comparisonBars.map(bar => (
          <div key={bar.key} className="rounded-lg bg-slate-50 border border-slate-200 px-3 py-2">
            <div className="flex items-center justify-between text-[11px] text-slate-500">
              <span>{bar.label}</span>
              <span>{bar.value.toLocaleString()}{bar.suffix}</span>
            </div>
            <div className="mt-2 h-1.5 rounded-full bg-white overflow-hidden">
              <div className={`h-full rounded-full ${bar.color}`} style={{ width: `${bar.percent}%` }} />
            </div>
          </div>
        ))}
      </div>

      <div className="mt-3 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
        <p className="text-[11px] font-semibold text-slate-600">핵심 환승 포인트</p>
        <div className="mt-1 space-y-1">
          {transfers.map(step => (
            <p key={step} className="text-xs text-slate-600">{step}</p>
          ))}
        </div>
      </div>

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
              prevLeg={i > 0 ? route.legs[i - 1] : null}
              nextLeg={i < route.legs.length - 1 ? route.legs[i + 1] : null}
              isLast={i === route.legs.length - 1}
            />
          ))}

          {showDebug && (
            <div className="mt-3 rounded-lg border border-dashed border-slate-300 bg-white px-3 py-3">
              <p className="text-[11px] font-semibold text-slate-600">엔진 디버그</p>
              <div className="mt-2 space-y-1">
                {debugFacts.map(fact => (
                  <p key={fact} className="text-xs text-slate-500">{fact}</p>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
