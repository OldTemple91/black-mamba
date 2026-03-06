import { useEffect, useRef } from 'react'

export function useNaverMap(containerId, options = {}) {
  const mapRef = useRef(null)

  useEffect(() => {
    if (!window.naver?.maps) return  // 인증 실패 시 maps가 null일 수 있음

    const map = new window.naver.maps.Map(containerId, {
      center: new window.naver.maps.LatLng(37.5547, 126.9706),
      zoom: 13,
      ...options
    })
    mapRef.current = map

    return () => { mapRef.current = null }
  }, [containerId, options])

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

  return { mapRef, addMarker, drawPolyline }
}
