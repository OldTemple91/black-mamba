import { useNaverMap } from '../../hooks/useNaverMap'

export default function NaverMap({ selectedRoute }) {
  const { mapRef, addMarker, drawPolyline } = useNaverMap('naver-map')

  return (
    <div id="naver-map" className="w-full h-96 rounded-lg shadow" />
  )
}
