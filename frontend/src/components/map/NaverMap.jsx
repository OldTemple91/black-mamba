import { useEffect, useRef } from 'react'
import { useNaverMap } from '../../hooks/useNaverMap'

// 수단별 폴리라인 색상
const LEG_COLOR = {
  TRANSIT:   '#0052A4',  // 파랑 (대중교통)
  WALK:      '#888888',  // 회색 (도보)
  BIKE:      '#2DA44E',  // 초록 (자전거)
  KICKBOARD: '#F97316',  // 주황 (킥보드)
}

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

    const isValid = p => p && Number.isFinite(p.lat) && Number.isFinite(p.lng)

    // 출발·도착 마커 (leg 경계점들)
    const points = selectedRoute.legs
      .flatMap(leg => [leg.start, leg.end])
      .filter(isValid)

    if (points.length === 0) return

    const uniquePoints = points.filter((p, idx, arr) => (
      idx === arr.findIndex(q => q.lat === p.lat && q.lng === p.lng)
    ))

    uniquePoints.forEach((point, idx) => {
      const marker = addMarker(point.lat, point.lng, point.name ?? `지점 ${idx + 1}`)
      if (marker) overlaysRef.current.push(marker)
    })

    // leg별 폴리라인: routeCoordinates 있으면 실제 도로 경로, 없으면 직선
    selectedRoute.legs.forEach(leg => {
      const color = LEG_COLOR[leg.type] ?? '#0052A4'
      const hasRoadPath = Array.isArray(leg.routeCoordinates) && leg.routeCoordinates.length > 1
      const coords = hasRoadPath
        ? leg.routeCoordinates
        : [leg.start, leg.end].filter(isValid)

      if (coords.length > 1) {
        const polyline = drawPolyline(coords, color)
        if (polyline) overlaysRef.current.push(polyline)
      }
    })

    // 지도 중심을 첫 번째 출발지로 이동
    const firstStart = selectedRoute.legs[0]?.start
    if (isValid(firstStart)) {
      mapRef.current.setCenter(new window.naver.maps.LatLng(firstStart.lat, firstStart.lng))
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
