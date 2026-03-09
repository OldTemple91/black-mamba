import { useEffect, useRef } from 'react'
import { useNaverMap } from '../../hooks/useNaverMap'

/**
 * @param {object}   props
 * @param {object}   [props.selectedRoute]   - 선택된 경로 (폴리라인/마커 표시)
 * @param {Function} [props.onMapClick]      - 지도 클릭 시 { lat, lng } 전달
 * @param {'origin'|'destination'|null} [props.mapMode] - 클릭 모드 표시용
 */
export default function NaverMap({ selectedRoute, onMapClick, mapMode }) {
  const { mapRef, addMarker, drawPolyline, setClickHandler } = useNaverMap('naver-map')
  const overlaysRef = useRef([])

  // 클릭 핸들러 등록/해제
  useEffect(() => {
    if (mapMode && onMapClick) {
      setClickHandler(onMapClick)
    } else {
      setClickHandler(null)
    }
    return () => setClickHandler(null)
  }, [mapMode, onMapClick, setClickHandler])

  // 선택된 경로 시각화
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

  const modeLabel = mapMode === 'origin'
    ? '📍 출발지를 클릭하세요'
    : mapMode === 'destination'
      ? '📍 목적지를 클릭하세요'
      : null

  return (
    <div className="relative">
      <div
        id="naver-map"
        className={`w-full h-96 rounded-lg shadow transition-all ${mapMode ? 'cursor-crosshair ring-2 ring-blue-400' : ''}`}
      />
      {modeLabel && (
        <div className="absolute inset-x-0 top-3 flex justify-center pointer-events-none">
          <span className="bg-blue-500 text-white text-sm px-4 py-1.5 rounded-full shadow-lg">
            {modeLabel}
          </span>
        </div>
      )}
    </div>
  )
}
