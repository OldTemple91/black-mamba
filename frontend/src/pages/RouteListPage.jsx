import { useEffect, useMemo, useState } from 'react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { searchRoutes } from '../api/routeApi'
import RouteCard from '../components/route/RouteCard'
import NaverMap from '../components/map/NaverMap'
import { buildComparisonContext, findBaselineRoute, getDebugFacts, getRecommendationReasons, getRiskBadges } from '../utils/routeInsights'

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

  const originName = searchParams.get('origin') || ''
  const destName   = searchParams.get('dest')   || ''
  const mobilityParam = searchParams.get('mobility') || ''
  const searchMode = searchParams.get('searchMode') || 'SPECIFIC'
  const recommendationPreference = searchParams.get('recommendationPreference') || 'RELIABILITY'

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
      })
    }).then(data => {
      setRoutes(data)
      setSelectedRoute(data[0] ?? null)
    }).catch(err => {
      setError(err?.message || '경로를 불러오지 못했습니다. 백엔드가 실행 중인지 확인하세요.')
      console.error(err)
    }).finally(() => setLoading(false))
  }, [originName, destName, mobilityParam, searchMode, recommendationPreference])

  const baselineRoute = useMemo(() => findBaselineRoute(routes), [routes])
  const comparisonContext = useMemo(() => buildComparisonContext(routes), [routes])
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

  if (loading) return (
    <div className="flex justify-center items-center h-screen">
      <div className="text-center">
        <p className="text-gray-500 text-lg">🐍 경로를 탐색 중입니다...</p>
        <p className="text-gray-400 text-sm mt-1">{originName} → {destName}</p>
      </div>
    </div>
  )

  if (error) return (
    <div className="flex justify-center items-center h-screen">
      <div className="max-w-sm text-center p-6 rounded-2xl border border-red-100 bg-red-50/70">
        <p className="text-sm font-semibold text-red-600">검색을 계속할 수 없습니다</p>
        <p className="mt-2 text-sm text-red-500 leading-6">{error}</p>
        <p className="mt-3 text-xs text-slate-500">
          출발지나 목적지를 조금 더 넓게 잡거나, 메인 화면에서 다시 검색해 주세요.
        </p>
        <div className="mt-5 flex justify-center gap-3">
          <button
            onClick={() => navigate('/')}
            className="rounded-full bg-slate-900 px-4 py-2 text-sm font-medium text-white"
          >
            다시 검색하기
          </button>
          <button
            onClick={() => navigate(-1)}
            className="rounded-full border border-slate-300 px-4 py-2 text-sm font-medium text-slate-600"
          >
            이전 화면
          </button>
        </div>
      </div>
    </div>
  )

  return (
    <div className="max-w-lg mx-auto p-4">
      {/* 헤더 */}
      <div className="flex items-center gap-2 mb-4">
        <button onClick={() => navigate('/')} className="text-gray-400 hover:text-gray-600">
          ← 
        </button>
        <div className="flex-1">
          <h2 className="text-lg font-bold text-gray-800">경로 결과</h2>
          <p className="text-xs text-gray-500">{originName} → {destName}</p>
        </div>
        <button
          onClick={() => setShowDebug(v => !v)}
          className={`text-xs px-3 py-1.5 rounded-full border transition ${
            showDebug
              ? 'bg-slate-900 text-white border-slate-900'
              : 'bg-white text-slate-600 border-slate-300'
          }`}
        >
          {showDebug ? '디버그 ON' : '디버그'}
        </button>
      </div>

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

      {/* 경로 카드 목록 */}
      <div className="space-y-3 mb-4">
        {routes.length === 0
          ? <p className="text-center text-gray-400 py-8">검색된 경로가 없습니다.</p>
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

      {/* 지도 */}
      <NaverMap selectedRoute={selectedRoute} />
    </div>
  )
}
