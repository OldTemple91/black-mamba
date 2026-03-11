import { useCallback, useEffect, useRef } from 'react'

export function useNaverMap(containerId, options = {}) {
  const mapRef = useRef(null)
  const clickListenerRef = useRef(null)
  // options는 렌더마다 새 {} 가 올 수 있으므로 ref로 고정 — 지도는 mount 시 1회만 생성
  const optionsRef = useRef(options)

  useEffect(() => {
    if (!window.naver?.maps) return

    const map = new window.naver.maps.Map(containerId, {
      center: new window.naver.maps.LatLng(37.5547, 126.9706),
      zoom: 13,
      ...optionsRef.current,
    })
    mapRef.current = map

    return () => {
      if (clickListenerRef.current) {
        window.naver.maps.Event.removeListener(clickListenerRef.current)
        clickListenerRef.current = null
      }
      mapRef.current = null
    }
  }, [containerId]) // ← options 제거: 렌더마다 새 {} 생성으로 지도가 destroy/recreate 되는 버그 방지

  const addMarker = (lat, lng, label = '') => {
    if (!mapRef.current) return null
    return new window.naver.maps.Marker({
      position: new window.naver.maps.LatLng(lat, lng),
      map: mapRef.current,
      title: label
    })
  }

  const addRichMarker = ({
    lat,
    lng,
    title = '',
    label = '',
    accentColor = '#2563EB',
    html
  }) => {
    if (!mapRef.current) return null

    const labelHtml = label
      ? `<div style="margin-left:8px;background:#fff;border:1px solid ${accentColor};border-radius:999px;padding:4px 8px;font-size:11px;font-weight:600;color:#111827;box-shadow:0 4px 12px rgba(15,23,42,0.12);white-space:nowrap;">${label}</div>`
      : ''

    return new window.naver.maps.Marker({
      position: new window.naver.maps.LatLng(lat, lng),
      map: mapRef.current,
      title,
      icon: {
        content: html ?? `
          <div style="display:flex;align-items:center;transform:translate(-12px,-12px);">
            <div style="width:14px;height:14px;border-radius:999px;background:${accentColor};border:3px solid #fff;box-shadow:0 4px 12px rgba(15,23,42,0.18);"></div>
            ${labelHtml}
          </div>
        `,
        anchor: new window.naver.maps.Point(12, 12)
      }
    })
  }

  const drawPolyline = (coords, color = '#0052A4') => {
    if (!mapRef.current) return
    return new window.naver.maps.Polyline({
      path: coords.map(c => new window.naver.maps.LatLng(c.lat, c.lng)),
      strokeColor: color,
      strokeWeight: 4,
      map: mapRef.current
    })
  }

  /**
   * 지도 클릭 핸들러 등록/해제
   * @param {({lat, lng}) => void | null} fn - null 전달 시 리스너 제거
   */
  const setClickHandler = useCallback((fn) => {
    if (!mapRef.current) return
    if (clickListenerRef.current) {
      window.naver.maps.Event.removeListener(clickListenerRef.current)
      clickListenerRef.current = null
    }
    if (fn) {
      clickListenerRef.current = window.naver.maps.Event.addListener(
        mapRef.current,
        'click',
        (e) => {
          const coord = e.coord
          if (coord) fn({ lat: coord.lat(), lng: coord.lng() })
        }
      )
    }
  }, [])

  return { mapRef, addMarker, addRichMarker, drawPolyline, setClickHandler }
}
