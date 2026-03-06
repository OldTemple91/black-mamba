import { useEffect, useRef } from 'react'
import { useNaverMap } from '../../hooks/useNaverMap'

export default function NaverMap({ selectedRoute }) {
  const { mapRef, addMarker, drawPolyline } = useNaverMap('naver-map')
  const overlaysRef = useRef([])

  useEffect(() => {
    if (!mapRef.current || !selectedRoute) return

    overlaysRef.current.forEach(overlay => overlay.setMap(null))
    overlaysRef.current = []

    const points = selectedRoute.legs
      .flatMap(leg => [leg.start, leg.end])
      .filter(p => p && Number.isFinite(p.lat) && Number.isFinite(p.lng))

    if (points.length === 0) return

    const uniquePoints = points.filter((p, idx, arr) => (
      idx === arr.findIndex(q => q.lat === p.lat && q.lng === p.lng)
    ))

    uniquePoints.forEach((point, idx) => {
      const marker = addMarker(point.lat, point.lng, point.name ?? `지점 ${idx + 1}`)
      if (marker) overlaysRef.current.push(marker)
    })

    const path = selectedRoute.legs
      .map(leg => leg.end)
      .filter(p => p && Number.isFinite(p.lat) && Number.isFinite(p.lng))

    if (selectedRoute.legs[0]?.start) {
      const start = selectedRoute.legs[0].start
      if (Number.isFinite(start.lat) && Number.isFinite(start.lng)) path.unshift(start)
    }

    if (path.length > 1) {
      const polyline = drawPolyline(path)
      if (polyline) overlaysRef.current.push(polyline)
    }

    const first = path[0]
    if (first) {
      const center = new window.naver.maps.LatLng(first.lat, first.lng)
      mapRef.current.setCenter(center)
    }

    return () => {
      overlaysRef.current.forEach(overlay => overlay.setMap(null))
      overlaysRef.current = []
    }
  }, [selectedRoute, mapRef, addMarker, drawPolyline])

  return (
    <div id="naver-map" className="w-full h-96 rounded-lg shadow" />
  )
}
