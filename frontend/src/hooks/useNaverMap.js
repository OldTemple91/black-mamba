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

  return { mapRef, addMarker, drawPolyline, setClickHandler }
}
