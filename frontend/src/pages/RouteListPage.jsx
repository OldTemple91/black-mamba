import { useEffect, useState } from 'react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { searchRoutes } from '../api/routeApi'
import RouteCard from '../components/route/RouteCard'
import NaverMap from '../components/map/NaverMap'

// 장소명 → 좌표 변환 (백엔드 NCP Geocoding REST API 호출)
const geocode = async (name) => {
  if (!name) return null
  try {
    const res = await fetch(`/api/geocode?query=${encodeURIComponent(name)}`)
    if (!res.ok) return null
    return await res.json()  // { lat, lng }
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
  }, [])

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
