import { useEffect, useMemo, useState } from 'react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { searchRoutes } from '../api/routeApi'
import RouteCard from '../components/route/RouteCard'
import RouteCardSkeleton from '../components/route/RouteCardSkeleton'
import NaverMap from '../components/map/NaverMap'
import ThemeToggle from '../components/common/ThemeToggle'
import { buildComparisonContext, countTransfers, findBaselineRoute, getDebugFacts, getRecommendationReasons, getRiskBadges, getWalkingDistance } from '../utils/routeInsights'

// 장소명 → 좌표 변환
// 우선순위: 1) "lat,lng" 좌표 문자열 직접 파싱  2) 네이버 지역 검색 API (POI 키워드)  3) 백엔드 NCP 지오코딩 폴백
const geocode = async (name) => {
  if (!name) return null

  // 1) 좌표 문자열 형식: "37.5547,126.9706" (지도 클릭 or 자동완성 좌표)
  const coordMatch = name.match(/^(-?\d+\.?\d*),(-?\d+\.?\d*)$/)
  if (coordMatch) {
    const lat = parseFloat(coordMatch[1])
    const lng = parseFloat(coordMatch[2])
    if (Number.isFinite(lat) && Number.isFinite(lng)) return { lat, lng }
  }

  // 2) 네이버 지역 검색 API — POI 키워드 지원 ("강남역", "홍대입구" 등)
  try {
    const res = await fetch(`/api/places?query=${encodeURIComponent(name)}`)
    if (res.ok) {
      const places = await res.json()
      if (places.length > 0) return { lat: places[0].lat, lng: places[0].lng }
    }
  } catch { /* 무시, 다음 폴백으로 */ }

  // 3) 백엔드 NCP Geocoding API 폴백 (도로명·지번 주소)
  try {
    const res = await fetch(`/api/geocode?query=${encodeURIComponent(name)}`)
    if (!res.ok) return null
    const data = await res.json()
    if (!Number.isFinite(data?.lat) || !Number.isFinite(data?.lng)) return null
    return data
  } catch {
    return null
  }
}

const SEOUL_CITY_HALL = { lat: 37.5663, lng: 126.9779 }

export default function RouteListPage() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const [routes, setRoutes] = useState([])
  const [selectedRoute, setSelectedRoute] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [showDebug, setShowDebug] = useState(false)
  const [comparisonRoutes, setComparisonRoutes] = useState([])
  const [comparisonLoading, setComparisonLoading] = useState(false)
  const [comparisonError, setComparisonError] = useState(null)

  const originName = searchParams.get('origin') || ''
  const destName   = searchParams.get('dest')   || ''
  const mobilityParam = searchParams.get('mobility') || ''
  const searchMode = searchParams.get('searchMode') || 'SPECIFIC'
  const recommendationPreference = searchParams.get('recommendationPreference') || 'RELIABILITY'
  const weather = searchParams.get('weather') || ''

  useEffect(() => {
    const mobility = mobilityParam.split(',').filter(Boolean)

    Promise.all([geocode(originName), geocode(destName)]).then(([originCoord, destCoord]) => {
      const origin = originCoord ?? SEOUL_CITY_HALL
      const dest   = destCoord   ?? { lat: 37.4979, lng: 127.0276 }

      return searchRoutes({
        originLat: origin.lat, originLng: origin.lng,
        destLat:   dest.lat,   destLng:   dest.lng,
        mobility,
        searchMode,
        recommendationPreference,
        weather,
      })
    }).then(data => {
      setRoutes(data)
      setSelectedRoute(data[0] ?? null)
    }).catch(err => {
      setError(err?.message || '경로를 불러오지 못했습니다. 백엔드가 실행 중인지 확인하세요.')
      console.error(err)
    }).finally(() => setLoading(false))
  }, [originName, destName, mobilityParam, searchMode, recommendationPreference, weather])

  const baselineRoute = useMemo(() => findBaselineRoute(routes), [routes])
  const comparisonContext = useMemo(() => buildComparisonContext(routes), [routes])
  const comparisonSelectedRoute = useMemo(() => comparisonRoutes[0] ?? null, [comparisonRoutes])
  const selectedReasons = useMemo(
    () => (selectedRoute ? getRecommendationReasons(selectedRoute, baselineRoute) : []),
    [selectedRoute, baselineRoute]
  )
  const selectedRisks = useMemo(
    () => (selectedRoute ? getRiskBadges(selectedRoute) : []),
    [selectedRoute]
  )
  const selectedDebugFacts = useMemo(
    () => (selectedRoute ? getDebugFacts(selectedRoute, baselineRoute, searchMode, recommendationPreference) : []),
    [selectedRoute, baselineRoute, searchMode, recommendationPreference]
  )
  const comparePreference = recommendationPreference === 'TIME_PRIORITY' ? 'RELIABILITY' : 'TIME_PRIORITY'
  const comparePreferenceLabel = comparePreference === 'TIME_PRIORITY' ? '시간 우선' : '신뢰도 우선'

  const handleLoadComparison = async () => {
    const mobility = mobilityParam.split(',').filter(Boolean)
    setComparisonLoading(true)
    setComparisonError(null)
    try {
      const [originCoord, destCoord] = await Promise.all([geocode(originName), geocode(destName)])
      const origin = originCoord ?? SEOUL_CITY_HALL
      const dest = destCoord ?? { lat: 37.4979, lng: 127.0276 }
      const data = await searchRoutes({
        originLat: origin.lat, originLng: origin.lng,
        destLat: dest.lat, destLng: dest.lng,
        mobility,
        searchMode,
        recommendationPreference: comparePreference,
      })
      setComparisonRoutes(data)
    } catch (err) {
      setComparisonError(err?.message || '비교 경로를 불러오지 못했습니다.')
    } finally {
      setComparisonLoading(false)
    }
  }

  const comparisonSummary = useMemo(() => {
    if (!selectedRoute || !comparisonSelectedRoute) return null
    return {
      minuteDelta: comparisonSelectedRoute.totalMinutes - selectedRoute.totalMinutes,
      walkDelta: getWalkingDistance(comparisonSelectedRoute) - getWalkingDistance(selectedRoute),
      transferDelta: countTransfers(comparisonSelectedRoute) - countTransfers(selectedRoute),
      recommendedType: comparisonSelectedRoute.type,
    }
  }, [selectedRoute, comparisonSelectedRoute])

  if (loading) return (
    <div className="min-h-screen">
      <div className="mx-auto max-w-7xl px-4 py-6 lg:py-8">
        {/* Skeleton 상단 sticky (실제 구조와 동일) */}
        <div className="sticky top-0 z-10 -mx-4 mb-5 border-b border-slate-200/60 dark:border-slate-700/40
                        bg-slate-50/80 dark:bg-slate-900/80 px-4 py-3 backdrop-blur-md">
          <div className="flex items-center gap-3">
            <div className="h-9 w-9 rounded-xl bg-slate-200 dark:bg-slate-700 animate-pulse" />
            <div className="min-w-0 flex-1 space-y-1.5">
              <div className="h-3 w-20 rounded bg-slate-200 dark:bg-slate-700 animate-pulse" />
              <div className="h-4 w-2/3 rounded bg-slate-200 dark:bg-slate-700 animate-pulse" />
            </div>
            <div className="h-9 w-9 rounded-full bg-slate-200 dark:bg-slate-700 animate-pulse" />
          </div>
        </div>

        <div className="lg:grid lg:grid-cols-[minmax(0,1fr)_minmax(380px,480px)] lg:gap-6">
          <div className="space-y-3">
            {/* 탐색 중 메시지 */}
            <div className="flex items-center justify-center gap-2 rounded-2xl
                            border border-blue-200 dark:border-blue-800
                            bg-blue-50/80 dark:bg-blue-900/30 px-4 py-3">
              <span className="inline-block h-2 w-2 animate-ping rounded-full bg-blue-500" />
              <span className="text-sm font-medium text-blue-700 dark:text-blue-300">
                🐍 경로 탐색 중 · {originName} → {destName}
              </span>
            </div>
            {/* 4개 skeleton 카드 */}
            {[1, 2, 3, 4].map(i => <RouteCardSkeleton key={i} />)}
          </div>

          <div className="mt-5 lg:mt-0">
            <div className="map-container rounded-3xl border border-slate-200 dark:border-slate-700
                            bg-white/70 dark:bg-slate-800/50 p-6 text-center text-sm text-slate-400 dark:text-slate-500
                            lg:sticky lg:top-24 lg:h-[calc(100vh-7rem)]
                            flex items-center justify-center">
              🗺 지도 로드 중…
            </div>
          </div>
        </div>
      </div>
    </div>
  )

  if (error) return (
    <div className="flex justify-center items-center min-h-screen px-4">
      <div className="max-w-md w-full text-center p-8 rounded-3xl border border-rose-200 dark:border-rose-900/50
                      bg-white/80 dark:bg-slate-800/60 shadow-xl">
        <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-2xl
                        bg-gradient-to-br from-rose-400 to-orange-400 shadow-lg shadow-rose-500/30">
          <span className="text-3xl">⚠️</span>
        </div>
        <h3 className="mt-4 text-base font-bold text-rose-700 dark:text-rose-300">
          검색을 계속할 수 없습니다
        </h3>
        <p className="mt-2 text-sm text-rose-600 dark:text-rose-400 leading-6">{error}</p>
        <p className="mt-3 text-xs text-slate-500 dark:text-slate-400">
          출발지나 목적지를 조금 더 넓게 잡거나, 메인 화면에서 다시 검색해 주세요.
        </p>
        <div className="mt-6 flex justify-center gap-3">
          <button
            onClick={() => navigate('/')}
            className="rounded-full bg-gradient-to-r from-blue-500 to-indigo-500
                       px-5 py-2 text-sm font-semibold text-white shadow-lg shadow-indigo-500/30
                       transition hover:brightness-110"
          >
            🏠 다시 검색
          </button>
          <button
            onClick={() => navigate(-1)}
            className="rounded-full border border-slate-300 dark:border-slate-600
                       bg-white dark:bg-slate-800 px-5 py-2 text-sm font-medium
                       text-slate-600 dark:text-slate-300 hover:border-slate-400"
          >
            ← 이전
          </button>
        </div>
      </div>
    </div>
  )

  return (
    <div className="min-h-screen">
      <div className="mx-auto max-w-7xl px-4 py-6 lg:py-8">

      {/* ──────── Sticky 상단: OD + 디버그 토글 + 다크모드 ──────── */}
      <div className="sticky top-0 z-10 -mx-4 mb-5 border-b border-slate-200/60 dark:border-slate-700/40
                      bg-slate-50/80 dark:bg-slate-900/80 px-4 py-3 backdrop-blur-md">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/')}
            title="메인으로"
            className="flex h-9 w-9 items-center justify-center rounded-xl border border-slate-200
                       dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400
                       shadow-sm transition hover:border-slate-300 dark:hover:border-slate-600
                       hover:text-slate-700 dark:hover:text-slate-200"
          >
            ←
          </button>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 text-[11px] font-semibold uppercase tracking-wider
                            text-slate-500 dark:text-slate-400">
              <span>🗺 Route Result</span>
              {weather && (
                <span className="rounded-full bg-blue-100 dark:bg-blue-900/60
                                 px-2 py-0.5 text-[10px] font-bold text-blue-700 dark:text-blue-300">
                  {weather === 'RAIN' ? '🌧 비' : weather === 'SNOW' ? '❄️ 눈' :
                   weather === 'HEAT' ? '☀️ 폭염' : weather === 'COLD' ? '🥶 혹한' : weather}
                </span>
              )}
            </div>
            <p className="mt-0.5 truncate text-sm font-semibold text-slate-800 dark:text-slate-100">
              <span className="text-blue-600 dark:text-blue-400">{originName}</span>
              <span className="mx-2 text-slate-400 dark:text-slate-500">→</span>
              <span className="text-rose-600 dark:text-rose-400">{destName}</span>
            </p>
          </div>
          <button
            onClick={() => setShowDebug(v => !v)}
            className={`rounded-full border px-3 py-1.5 text-xs font-medium transition ${
              showDebug
                ? 'border-slate-900 dark:border-slate-200 bg-slate-900 dark:bg-slate-200 text-white dark:text-slate-900'
                : 'border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:border-slate-400'
            }`}
          >
            {showDebug ? '🔧 디버그 ON' : '🔧 디버그'}
          </button>
          <ThemeToggle />
        </div>
      </div>

      {/* ──────── 데스크톱: 카드 좌측 + 지도 우측 sticky ──────── */}
      <div className="lg:grid lg:grid-cols-[minmax(0,1fr)_minmax(380px,480px)] lg:gap-6">

      {selectedRoute && (
        <div className="mb-4 rounded-2xl border border-slate-200 bg-white px-4 py-4 shadow-sm">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="text-[11px] uppercase tracking-[0.18em] text-slate-400">Selected Route</p>
              <h3 className="mt-1 text-base font-semibold text-slate-800">
                {selectedRoute.totalMinutes}분 경로 분석
              </h3>
            </div>
            {selectedRoute.recommended && (
              <span className="text-xs bg-blue-500 text-white px-2 py-1 rounded-full">추천 경로</span>
            )}
          </div>

          <div className="mt-3 flex flex-wrap gap-2">
            {selectedReasons.map(reason => (
              <span key={reason} className="text-xs px-2 py-1 rounded-full bg-sky-50 text-sky-700 border border-sky-200">
                {reason}
              </span>
            ))}
            {selectedRisks.map(risk => (
              <span key={risk.label} className={`text-xs px-2 py-1 rounded-full ${risk.className}`}>
                {risk.label}
              </span>
            ))}
          </div>

          {showDebug && (
            <div className="mt-4 rounded-xl border border-dashed border-slate-300 bg-slate-50 px-3 py-3">
              <p className="text-[11px] font-semibold text-slate-600">엔진 진단 요약</p>
              <div className="mt-2 space-y-1">
                {selectedDebugFacts.map(fact => (
                  <p key={fact} className="text-xs text-slate-500">{fact}</p>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      <div className="mb-4 rounded-2xl border border-slate-200 bg-white px-4 py-4 shadow-sm">
        <div className="flex items-start justify-between gap-3">
          <div>
            <p className="text-[11px] uppercase tracking-[0.18em] text-slate-400">Preference Compare</p>
            <h3 className="mt-1 text-base font-semibold text-slate-800">
              {recommendationPreference === 'TIME_PRIORITY' ? '시간 우선' : '신뢰도 우선'} 결과를 기준으로 비교
            </h3>
            <p className="mt-1 text-xs text-slate-500">
              반대 추천 성향 결과를 필요할 때만 조회합니다.
            </p>
          </div>
          <button
            onClick={handleLoadComparison}
            disabled={comparisonLoading}
            className={`rounded-full px-4 py-2 text-sm font-medium transition ${
              comparisonLoading
                ? 'bg-slate-200 text-slate-500'
                : 'bg-slate-900 text-white hover:bg-slate-800'
            }`}
          >
            {comparisonLoading ? '비교 불러오는 중...' : `${comparePreferenceLabel}와 비교`}
          </button>
        </div>

        {comparisonError && (
          <p className="mt-3 text-sm text-rose-600">{comparisonError}</p>
        )}

        {comparisonSelectedRoute && comparisonSummary && (
          <div className="mt-4 rounded-xl border border-slate-200 bg-slate-50 px-4 py-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <p className="text-xs font-semibold text-slate-700">{comparePreferenceLabel} 대표 추천</p>
                <p className="mt-1 text-sm text-slate-600">
                  {comparisonSelectedRoute.type} · {comparisonSelectedRoute.totalMinutes}분
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <span className={`text-xs px-2 py-1 rounded-full border ${
                  comparisonSummary.minuteDelta < 0
                    ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                    : comparisonSummary.minuteDelta > 0
                      ? 'border-amber-200 bg-amber-50 text-amber-700'
                      : 'border-slate-200 bg-white text-slate-600'
                }`}>
                  시간 {comparisonSummary.minuteDelta === 0 ? '동일' : `${Math.abs(comparisonSummary.minuteDelta)}분 ${comparisonSummary.minuteDelta < 0 ? '빠름' : '느림'}`}
                </span>
                <span className={`text-xs px-2 py-1 rounded-full border ${
                  comparisonSummary.walkDelta < 0
                    ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                    : comparisonSummary.walkDelta > 0
                      ? 'border-amber-200 bg-amber-50 text-amber-700'
                      : 'border-slate-200 bg-white text-slate-600'
                }`}>
                  도보 {comparisonSummary.walkDelta === 0 ? '동일' : `${Math.abs(comparisonSummary.walkDelta)}m ${comparisonSummary.walkDelta < 0 ? '적음' : '많음'}`}
                </span>
                <span className={`text-xs px-2 py-1 rounded-full border ${
                  comparisonSummary.transferDelta < 0
                    ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                    : comparisonSummary.transferDelta > 0
                      ? 'border-amber-200 bg-amber-50 text-amber-700'
                      : 'border-slate-200 bg-white text-slate-600'
                }`}>
                  환승 {comparisonSummary.transferDelta === 0 ? '동일' : `${Math.abs(comparisonSummary.transferDelta)}회 ${comparisonSummary.transferDelta < 0 ? '적음' : '많음'}`}
                </span>
              </div>
            </div>

            <div className="mt-3 grid gap-3 md:grid-cols-2">
              <div className="rounded-xl border border-slate-200 bg-white px-3 py-3">
                <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-400">현재 추천 성향</p>
                <p className="mt-2 text-sm font-semibold text-slate-800">{recommendationPreference === 'TIME_PRIORITY' ? '시간 우선' : '신뢰도 우선'}</p>
                <p className="mt-1 text-sm text-slate-600">{selectedRoute.type} · {selectedRoute.totalMinutes}분</p>
              </div>
              <div className="rounded-xl border border-slate-200 bg-white px-3 py-3">
                <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-400">비교 성향</p>
                <p className="mt-2 text-sm font-semibold text-slate-800">{comparePreferenceLabel}</p>
                <p className="mt-1 text-sm text-slate-600">{comparisonSelectedRoute.type} · {comparisonSelectedRoute.totalMinutes}분</p>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* 경로 카드 목록 */}
      <div className="space-y-3 lg:col-start-1 lg:row-start-1">
        {routes.length === 0
          ? (
            <div className="rounded-3xl border border-dashed border-slate-300 bg-white/60 py-12 text-center">
              <p className="text-4xl">🕳</p>
              <p className="mt-3 text-sm text-slate-500">검색된 경로가 없습니다.</p>
            </div>
          )
          : routes.map(route => (
            <RouteCard
              key={route.routeId}
              route={route}
              selected={selectedRoute?.routeId === route.routeId}
              onClick={() => setSelectedRoute(route)}
              baselineRoute={baselineRoute}
              comparisonContext={comparisonContext}
              searchMode={searchMode}
              recommendationPreference={recommendationPreference}
              showDebug={showDebug}
            />
          ))
        }
      </div>

      {/* ──────── 우측 sticky 지도 (데스크톱) / 하단 일반 (모바일) ──────── */}
      <div className="mt-5 lg:mt-0 lg:col-start-2 lg:row-start-1">
        <div className="map-container overflow-hidden rounded-3xl border border-slate-200 bg-white/70
                        lg:sticky lg:top-24 lg:h-[calc(100vh-7rem)]">
          <div className="flex items-center justify-between border-b border-slate-100 bg-white/80
                          px-4 py-2.5 backdrop-blur">
            <p className="text-xs font-semibold text-slate-600">
              🗺 {selectedRoute ? `선택된 경로 · ${selectedRoute.totalMinutes}분` : '경로 미리보기'}
            </p>
            {selectedRoute?.recommended && (
              <span className="text-[10px] font-semibold text-blue-600">⭐ 추천</span>
            )}
          </div>
          <div className="h-[400px] lg:h-full">
            <NaverMap selectedRoute={selectedRoute} />
          </div>
        </div>
      </div>

      </div>{/* end grid */}
      </div>{/* end container */}
    </div>
  )
}
