import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { searchRoutes } from '../api/routeApi'
import RouteCard from '../components/route/RouteCard'
import NaverMap from '../components/map/NaverMap'

export default function RouteListPage() {
  const [searchParams] = useSearchParams()
  const [routes, setRoutes] = useState([])
  const [selectedRoute, setSelectedRoute] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const mobility = searchParams.get('mobility')?.split(',').filter(Boolean) || []
    searchRoutes({
      originLat: 37.5547, originLng: 126.9706,
      destLat: 37.4979, destLng: 127.0276,
      mobility
    }).then(data => {
      setRoutes(data)
      setSelectedRoute(data[0])
    }).finally(() => setLoading(false))
  }, [])

  if (loading) return (
    <div className="flex justify-center items-center h-screen">
      <p className="text-gray-500">경로를 탐색 중입니다...</p>
    </div>
  )

  return (
    <div className="max-w-lg mx-auto p-4">
      <h2 className="text-lg font-bold mb-3">경로 결과</h2>
      <div className="space-y-3 mb-4">
        {routes.map(route => (
          <RouteCard
            key={route.routeId}
            route={route}
            selected={selectedRoute?.routeId === route.routeId}
            onClick={() => setSelectedRoute(route)}
          />
        ))}
      </div>
      <NaverMap selectedRoute={selectedRoute} />
    </div>
  )
}
