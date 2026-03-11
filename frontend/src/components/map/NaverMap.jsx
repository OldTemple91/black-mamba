import { useEffect, useRef } from 'react'
import { useNaverMap } from '../../hooks/useNaverMap'

// 수단별 폴리라인 색상
const LEG_COLOR = {
  TRANSIT:   '#0052A4',  // 파랑 (대중교통)
  WALK:      '#888888',  // 회색 (도보)
  BIKE:      '#2DA44E',  // 초록 (자전거)
  KICKBOARD: '#F97316',  // 주황 (킥보드)
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

function getTransitLabel(leg) {
  if (leg.transitInfo?.lineName) return leg.transitInfo.lineName
  if (leg.mode === 'SUBWAY') return '지하철'
  if (leg.mode === 'BUS') return '버스'
  return '대중교통'
}

function getLegActionLabel(leg) {
  if (leg.type === 'TRANSIT') return `${getTransitLabel(leg)} 탑승`
  if (leg.type === 'BIKE') return '자전거 탑승'
  if (leg.type === 'KICKBOARD') return '킥보드 탑승'
  return '도보 이동'
}

function getLegSummary(leg) {
  if (leg.type === 'TRANSIT') {
    const modeLabel = leg.mode === 'SUBWAY' ? '지하철' : leg.mode === 'BUS' ? '버스' : '대중교통'
    return `${modeLabel} ${getTransitLabel(leg)}`
  }
  if (leg.type === 'BIKE') return leg.mobilityInfo?.stationName ? `${leg.mobilityInfo.stationName}에서 자전거` : '자전거 이동'
  if (leg.type === 'KICKBOARD') return leg.mobilityInfo?.operatorName ? `${leg.mobilityInfo.operatorName} 킥보드` : '킥보드 이동'
  return '도보 이동'
}

function markerDetailHtml(point) {
  const details = point.details
    .map(detail => `<li style="margin:4px 0;">${escapeHtml(detail)}</li>`)
    .join('')

  return `
    <div style="padding:12px 14px;min-width:220px;max-width:280px;font-size:12px;line-height:1.5;color:#111827;">
      <div style="font-weight:700;font-size:13px;margin-bottom:4px;">${escapeHtml(point.name)}</div>
      <div style="color:#2563EB;font-weight:600;margin-bottom:6px;">${escapeHtml(point.label)}</div>
      <ul style="padding-left:16px;margin:0;">${details}</ul>
    </div>
  `
}

function buildMarkerPoints(route) {
  const markerMap = new Map()
  const firstLeg = route.legs[0]

  if (firstLeg?.start && Number.isFinite(firstLeg.start.lat) && Number.isFinite(firstLeg.start.lng)) {
    markerMap.set(`${firstLeg.start.lat}:${firstLeg.start.lng}`, {
      lat: firstLeg.start.lat,
      lng: firstLeg.start.lng,
      name: firstLeg.start.name ?? '출발지',
      label: '출발',
      accentColor: '#111827',
      details: ['실제 출발 지점'],
    })
  }

  route.legs.forEach((leg, index) => {
    if (leg.type === 'WALK') return

    const start = leg.start
    if (!start || !Number.isFinite(start.lat) || !Number.isFinite(start.lng)) return

    const key = `${start.lat}:${start.lng}`
    const existing = markerMap.get(key)
    const point = existing ?? {
      lat: start.lat,
      lng: start.lng,
      name: start.name ?? `지점 ${index + 1}`,
      label: getLegActionLabel(leg),
      accentColor: LEG_COLOR[leg.type] ?? '#2563EB',
      details: []
    }

    point.label = existing ? `환승: ${getLegSummary(leg)}` : point.label
    point.details.push(getLegSummary(leg))

    if (leg.type === 'TRANSIT') {
      if (leg.transitInfo?.stationCount > 0) point.details.push(`${leg.transitInfo.stationCount}정거장 이동`)
      if (leg.mode === 'SUBWAY') point.details.push('지하철 구간')
      if (leg.mode === 'BUS') point.details.push('버스 구간')
    }

    if (leg.type === 'BIKE' || leg.type === 'KICKBOARD') {
      if (index > 0 && route.legs[index - 1]?.type === 'WALK') {
        point.details.push('도보 접근 후 탑승')
      }
      if (leg.mobilityInfo?.operatorName) point.details.push(`운영사: ${leg.mobilityInfo.operatorName}`)
      if (leg.mobilityInfo?.deviceId) point.details.push(`기기 ID: ${leg.mobilityInfo.deviceId}`)
      if (leg.mobilityInfo?.stationName) point.details.push(`대여 정류소: ${leg.mobilityInfo.stationName}`)
      if (leg.mobilityInfo?.stationId) point.details.push(`정류소 ID: ${leg.mobilityInfo.stationId}`)
      if (leg.mobilityInfo?.availableCount > 0) point.details.push(`대여 가능: ${leg.mobilityInfo.availableCount}대`)
      if (leg.mobilityInfo?.rackTotalCount > 0) point.details.push(`거치대 수: ${leg.mobilityInfo.rackTotalCount}`)
      if (leg.mobilityInfo?.batteryLevel > 0 && leg.type === 'KICKBOARD') point.details.push(`배터리: ${leg.mobilityInfo.batteryLevel}%`)
    }

    markerMap.set(key, point)
  })

  const lastLeg = route.legs.at(-1)
  if (lastLeg?.end && Number.isFinite(lastLeg.end.lat) && Number.isFinite(lastLeg.end.lng)) {
    const key = `${lastLeg.end.lat}:${lastLeg.end.lng}`
    const existing = markerMap.get(key)
    const arrival = existing ?? {
      lat: lastLeg.end.lat,
      lng: lastLeg.end.lng,
      name: lastLeg.end.name ?? '도착지',
      label: '도착',
      accentColor: '#111827',
      details: []
    }
    arrival.details.push('최종 도착 지점')
    if (lastLeg.mobilityInfo?.dropoffStationName) {
      arrival.details.push(`근처 반납 정류소: ${lastLeg.mobilityInfo.dropoffStationName}`)
    }
    markerMap.set(key, arrival)
  }

  return Array.from(markerMap.values())
}

/**
 * @param {object}   props
 * @param {object}   [props.selectedRoute]   - 선택된 경로 (폴리라인/마커 표시)
 * @param {Function} [props.onMapClick]      - 지도 클릭 시 { lat, lng } 전달
 * @param {'origin'|'destination'|null} [props.mapMode] - 클릭 모드 표시용
 */
export default function NaverMap({ selectedRoute, onMapClick, mapMode }) {
  const { mapRef, addRichMarker, drawPolyline, setClickHandler } = useNaverMap('naver-map')
  const overlaysRef = useRef([])
  const clearOverlays = () => {
    overlaysRef.current.forEach(overlay => {
      if (typeof overlay?.close === 'function') overlay.close()
      if (typeof overlay?.setMap === 'function') overlay.setMap(null)
    })
    overlaysRef.current = []
  }

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

    clearOverlays()

    const isValid = p => p && Number.isFinite(p.lat) && Number.isFinite(p.lng)

    // 출발·도착 마커 (leg 경계점들)
    const markerPoints = buildMarkerPoints(selectedRoute)

    if (markerPoints.length === 0) return

    markerPoints.forEach(point => {
      const marker = addRichMarker({
        lat: point.lat,
        lng: point.lng,
        title: point.name,
        label: point.label,
        accentColor: point.accentColor
      })
      if (!marker) return

      const infoWindow = new window.naver.maps.InfoWindow({
        content: markerDetailHtml(point),
        borderWidth: 0,
        backgroundColor: '#fff',
        disableAnchor: false
      })

      window.naver.maps.Event.addListener(marker, 'mouseover', () => {
        infoWindow.open(mapRef.current, marker)
      })
      window.naver.maps.Event.addListener(marker, 'mouseout', () => {
        infoWindow.close()
      })
      window.naver.maps.Event.addListener(marker, 'click', () => {
        infoWindow.getMap() ? infoWindow.close() : infoWindow.open(mapRef.current, marker)
      })

      overlaysRef.current.push(marker, infoWindow)
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
      clearOverlays()
    }
  }, [selectedRoute, mapRef, addRichMarker, drawPolyline])

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
      <div className="absolute left-3 bottom-3 rounded-xl bg-white/95 backdrop-blur border border-slate-200 shadow-lg px-3 py-2">
        <p className="text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-400">Legend</p>
        <div className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1">
          {Object.entries({
            대중교통: LEG_COLOR.TRANSIT,
            자전거: LEG_COLOR.BIKE,
            킥보드: LEG_COLOR.KICKBOARD,
            도보: LEG_COLOR.WALK,
          }).map(([label, color]) => (
            <div key={label} className="flex items-center gap-2 text-xs text-slate-600">
              <span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: color }} />
              <span>{label}</span>
            </div>
          ))}
        </div>
      </div>
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
