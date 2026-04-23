import { useState } from 'react'
import LegItem from './LegItem'
import RouteTimelineBar from './RouteTimelineBar'
import {
  getComparisonBars,
  getCostBreakdown,
  getDebugFacts,
  getFallbackDiagnostics,
  getGenerationDiagnostics,
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
  recommendationPreference,
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
  const diagnostics = getGenerationDiagnostics(route)
  const fallbackDiagnostics = getFallbackDiagnostics(route)
  const comparisonBars = comparisonContext ? getComparisonBars(route, comparisonContext) : []
  const costBreakdown = getCostBreakdown(route)
  const debugFacts = showDebug ? getDebugFacts(route, baselineRoute, searchMode, recommendationPreference) : []

  return (
    <div
      onClick={onClick}
      className={`group relative cursor-pointer rounded-2xl border p-4 transition-all animate-fade-in-up
        ${selected
          ? 'border-blue-500 bg-blue-50/70 dark:bg-blue-900/30 shadow-lg shadow-blue-500/10 ring-2 ring-blue-300/40 dark:ring-blue-500/40'
          : route.recommended
            ? 'border-transparent bg-white dark:bg-slate-800/80 shadow-md shadow-indigo-500/5 recommended-ring'
            : 'border-slate-200 dark:border-slate-700 bg-white/90 dark:bg-slate-800/60 hover:border-slate-300 dark:hover:border-slate-600 hover:shadow-sm'}`}
    >
      {/* ──────── 헤더: 배지 + 경로 타입 + 시간/비용 ──────── */}
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <div className="flex flex-wrap items-center gap-1.5">
            {route.recommended && (
              <span className="inline-flex items-center gap-1 rounded-full
                               bg-gradient-to-r from-blue-500 via-indigo-500 to-purple-500
                               px-2.5 py-0.5 text-[11px] font-semibold text-white shadow-sm shadow-indigo-500/40">
                ⭐ 추천
              </span>
            )}
            <span className="text-[11px] font-medium tracking-wide text-slate-500 dark:text-slate-400">
              {routeLabel}
            </span>
            {risks.map(risk => (
              <span key={risk.label} className={`rounded-full px-2 py-0.5 text-[11px] ${risk.className}`}>
                {risk.label}
              </span>
            ))}
          </div>
          {/* 이동수단 체인 (요약 텍스트) */}
          <p className="truncate text-[13px] text-slate-600 dark:text-slate-400">
            {route.legs.map(summarizeLeg).join(' → ')}
          </p>
        </div>
        <div className="shrink-0 text-right">
          <div className="flex items-baseline gap-0.5">
            <span className="text-2xl font-bold tracking-tight text-slate-900 dark:text-slate-50 tabular-nums">
              {route.totalMinutes}
            </span>
            <span className="text-xs font-medium text-slate-500 dark:text-slate-400">분</span>
          </div>
          {route.totalCostWon > 0 && (
            <p className="text-[11px] font-medium text-slate-500 dark:text-slate-400 tabular-nums">
              {route.totalCostWon.toLocaleString()}원
            </p>
          )}
          {costBreakdown.length > 0 && (
            <p className="mt-0.5 max-w-[160px] text-right text-[10px] text-slate-400 dark:text-slate-500">
              {costBreakdown.map(item => `${item.label} ${item.amountWon.toLocaleString()}`).join(' · ')}
            </p>
          )}
        </div>
      </div>

      {/* ──────── Route Leg Timeline Bar (Citymapper 스타일) ──────── */}
      <RouteTimelineBar legs={route.legs} />

      {/* ──────── 절약 시간 & Carbon 배지 — 하나의 메타 라인에 ──────── */}
      {(route.comparison?.savedMinutes > 0 || route.carbon) && (
        <div className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1.5">
          {route.comparison?.savedMinutes > 0 && (
            <span className="inline-flex items-center gap-1 text-[11px] font-medium text-amber-700">
              🔥 {route.comparison.savedMinutes}분 단축
            </span>
          )}
          {route.carbon && (
            <>
              <span
                className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-semibold ${
                  route.carbon.eco
                    ? 'border-emerald-300 bg-emerald-50 text-emerald-700'
                    : 'border-slate-200 bg-slate-50 text-slate-600'
                }`}
                title={`평균 탄소 강도 ${route.carbon.gramsPerKm.toFixed(1)} g/km`}
              >
                🌱 {route.carbon.grams >= 1000
                  ? `${(route.carbon.grams / 1000).toFixed(1)}kg`
                  : `${Math.round(route.carbon.grams)}g`} CO₂
              </span>
              {route.carbon.eco && (
                <span className="rounded-full border border-emerald-400 bg-emerald-100 px-2 py-0.5
                                 text-[11px] font-semibold text-emerald-800">
                  🌿 친환경
                </span>
              )}
              {route.carbon.savedVsCarGrams > 100 && (
                <span className="text-[11px] font-medium text-emerald-600">
                  자가용 −{route.carbon.savedVsCarGrams >= 1000
                    ? `${(route.carbon.savedVsCarGrams / 1000).toFixed(1)}kg`
                    : `${Math.round(route.carbon.savedVsCarGrams)}g`}
                </span>
              )}
            </>
          )}
        </div>
      )}

      {/* F-1: 자가용 대비 비교 */}
      {route.carComparison && (
        <div className="mt-3 rounded-lg border border-emerald-200 dark:border-emerald-800/60
                        bg-emerald-50 dark:bg-emerald-900/30 px-3 py-2">
          <div className="flex items-center gap-2 text-[11px] font-semibold text-emerald-700 dark:text-emerald-300">
            <span>🚗</span>
            <span>자가용 대비</span>
          </div>
          <div className="mt-1.5 flex flex-wrap gap-3 text-xs">
            <span className={route.carComparison.timeDiffMinutes >= 0
                ? 'text-slate-600 dark:text-slate-400'
                : 'text-emerald-700 dark:text-emerald-300 font-semibold'}>
              {route.carComparison.timeDiffMinutes >= 0 ? '+' : ''}
              {route.carComparison.timeDiffMinutes}분
            </span>
            <span className={route.carComparison.costSavedWon > 0
                ? 'text-emerald-700 dark:text-emerald-300 font-semibold'
                : 'text-slate-600 dark:text-slate-400'}>
              {route.carComparison.costSavedWon > 0 ? '-' : '+'}
              {Math.abs(route.carComparison.costSavedWon).toLocaleString()}원
            </span>
            <span className="text-emerald-700 dark:text-emerald-300 font-semibold">
              -{route.carComparison.co2ReducedGrams >= 1000
                  ? `${(route.carComparison.co2ReducedGrams / 1000).toFixed(1)}kg`
                  : `${Math.round(route.carComparison.co2ReducedGrams)}g`}
              {' CO₂'}
            </span>
          </div>
          <p className="mt-1.5 text-[11px] text-emerald-800 dark:text-emerald-200/90">
            {route.carComparison.narrative}
          </p>
        </div>
      )}

      <div className="mt-3 flex flex-wrap gap-2">
        {reasons.map(reason => (
          <span key={reason} className="text-xs px-2 py-1 rounded-full
                                        bg-sky-50 dark:bg-sky-900/40
                                        text-sky-700 dark:text-sky-300
                                        border border-sky-200 dark:border-sky-800/60">
            {reason}
          </span>
        ))}
      </div>

      {hubs.length > 0 && (
        <div className="mt-3 rounded-lg border border-violet-200 dark:border-violet-800/60
                        bg-violet-50 dark:bg-violet-900/30 px-3 py-2">
          <p className="text-[11px] font-semibold text-violet-700 dark:text-violet-300">경로 허브</p>
          <div className="mt-2 flex flex-wrap gap-2">
            {hubs.map(hub => (
              <span
                key={`${hub.label}-${hub.detail}`}
                className={`text-[11px] px-2 py-1 rounded-full border ${
                  hub.tone === 'candidate'
                    ? 'border-amber-200 dark:border-amber-800/60 bg-amber-50 dark:bg-amber-900/30 text-amber-700 dark:text-amber-300'
                    : 'border-violet-200 dark:border-violet-800/60 bg-white dark:bg-slate-800 text-violet-700 dark:text-violet-300'
                }`}
              >
                {hub.label} · {hub.detail}
                {hub.source === 'selected-candidate' && (
                  <>
                    {' · '}
                    {hub.metadata.selectionPhase === 'FIRST_MILE' ? '후보(출발)' : hub.metadata.selectionPhase === 'LAST_MILE' ? '후보(도착)' : '후보'}
                    {hub.strategyLabel && <> · {hub.strategyLabel}</>}
                  </>
                )}
              </span>
            ))}
          </div>
        </div>
      )}

      {diagnostics.length > 0 && (
        <div className="mt-3 rounded-lg border border-amber-200 dark:border-amber-800/60
                        bg-amber-50 dark:bg-amber-900/30 px-3 py-2">
          <p className="text-[11px] font-semibold text-amber-700 dark:text-amber-300">혼합 경로 진단</p>
          <div className="mt-2 space-y-1">
            {diagnostics.map(item => (
              <p
                key={item.message}
                className={`text-xs ${
                  item.tone === 'risk'
                    ? 'text-rose-700 dark:text-rose-400'
                    : item.tone === 'caution'
                      ? 'text-amber-800 dark:text-amber-200'
                      : 'text-slate-600 dark:text-slate-400'
                }`}
              >
                {item.message}
              </p>
            ))}
          </div>
        </div>
      )}

      {fallbackDiagnostics.length > 0 && (
        <div className="mt-3 rounded-lg border border-slate-200 dark:border-slate-700
                        bg-slate-50 dark:bg-slate-900/50 px-3 py-2">
          <p className="text-[11px] font-semibold text-slate-700 dark:text-slate-300">Fallback / 추정 정보</p>
          <div className="mt-2 space-y-1">
            {fallbackDiagnostics.map(item => (
              <p
                key={item.message}
                className={`text-xs ${
                  item.tone === 'risk'
                    ? 'text-rose-700 dark:text-rose-400'
                    : item.tone === 'caution'
                      ? 'text-amber-800 dark:text-amber-200'
                      : 'text-slate-600 dark:text-slate-400'
                }`}
              >
                {item.message}
              </p>
            ))}
          </div>
        </div>
      )}

      <div className="mt-3 grid grid-cols-2 gap-2">
        {comparisonBars.map(bar => (
          <div key={bar.key} className="rounded-lg bg-slate-50 dark:bg-slate-900/50
                                        border border-slate-200 dark:border-slate-700 px-3 py-2">
            <div className="flex items-center justify-between text-[11px] text-slate-500 dark:text-slate-400">
              <span>{bar.label}</span>
              <span>{bar.value.toLocaleString()}{bar.suffix}</span>
            </div>
            <div className="mt-2 h-1.5 rounded-full bg-white dark:bg-slate-800 overflow-hidden">
              <div className={`h-full rounded-full ${bar.color}`} style={{ width: `${bar.percent}%` }} />
            </div>
          </div>
        ))}
      </div>

      <div className="mt-3 rounded-lg border border-slate-200 dark:border-slate-700
                      bg-slate-50 dark:bg-slate-900/50 px-3 py-2">
        <p className="text-[11px] font-semibold text-slate-600 dark:text-slate-300">핵심 환승 포인트</p>
        <div className="mt-1 space-y-1">
          {transfers.map(step => (
            <p key={step} className="text-xs text-slate-600 dark:text-slate-400">{step}</p>
          ))}
        </div>
      </div>

      {/* 상세 토글 */}
      <button
        onClick={e => { e.stopPropagation(); setExpanded(v => !v) }}
        className="text-xs text-blue-500 dark:text-blue-400 mt-2 hover:underline"
      >
        {expanded ? '접기 ▲' : '상세 보기 ▼'}
      </button>

      {/* 상세 — 타임라인 스타일 */}
      {expanded && (
        <div className="mt-3 border-t border-slate-200 dark:border-slate-700 pt-3">
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
            <div className="mt-3 rounded-lg border border-dashed border-slate-300 dark:border-slate-600
                            bg-white dark:bg-slate-900 px-3 py-3">
              <p className="text-[11px] font-semibold text-slate-600 dark:text-slate-300">엔진 디버그</p>
              <div className="mt-2 space-y-1">
                {debugFacts.map(fact => (
                  <p key={fact} className="text-xs text-slate-500 dark:text-slate-400">{fact}</p>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
