import { useEffect, useState } from 'react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { searchRoutes } from '../api/routeApi'
import RouteCard from '../components/route/RouteCard'
import NaverMap from '../components/map/NaverMap'

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

  const originName = searchParams.get('origin') || ''
  const destName   = searchParams.get('dest')   || ''
  const mobilityParam = searchParams.get('mobility') || ''
  const searchMode = searchParams.get('searchMode') || 'SPECIFIC'

  useEffect(() => {
    const mobility = mobilityParam.split(',').filter(Boolean)

    Promise.all([geocode(originName), geocode(destName)]).then(([originCoord, destCoord]) => {
      const origin = originCoord ?? SEOUL_CITY_HALL
      const dest   = destCoord   ?? { lat: 37.4979, lng: 127.0276 }

      return searchRoutes({
        originLat: origin.lat, originLng: origin.lng,
        destLat:   dest.lat,   destLng:   dest.lng,
        mobility,
        searchMode
      })
    }).then(data => {
      setRoutes(data)
      setSelectedRoute(data[0] ?? null)
    }).catch(err => {
      setError('경로를 불러오지 못했습니다. 백엔드가 실행 중인지 확인하세요.')
      console.error(err)
    }).finally(() => setLoading(false))
  }, [originName, destName, mobilityParam, searchMode])

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
      <div className="text-center p-6">
        <p className="text-red-500">{error}</p>
        <button onClick={() => navigate('/')} className="mt-4 text-blue-500 underline">
          메인으로 돌아가기
        </button>
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
        <div>
          <h2 className="text-lg font-bold text-gray-800">경로 결과</h2>
          <p className="text-xs text-gray-500">{originName} → {destName}</p>
        </div>
      </div>

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
            />
          ))
        }
      </div>

      {/* 지도 */}
      <NaverMap selectedRoute={selectedRoute} />
    </div>
  )
}
