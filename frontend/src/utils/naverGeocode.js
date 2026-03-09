/**
 * 네이버 지도 SDK 클라이언트 사이드 지오코딩
 * - geocoder 서브모듈(submodules=geocoder) 필요
 * - 백엔드 없이 동작, "강남역" 같은 POI 키워드도 지원
 *
 * @param {string} query - 장소명 또는 주소 (예: "강남역", "서울특별시 강남구 테헤란로")
 * @returns {Promise<Array<{name: string, lat: number, lng: number}>>}
 */
export function naverGeocode(query) {
  return new Promise((resolve) => {
    const svc = window.naver?.maps?.Service
    if (!svc?.geocode) {
      resolve([])
      return
    }

    svc.geocode({ query }, (status, response) => {
      if (status !== svc.Status.OK) {
        resolve([])
        return
      }

      const items = response?.v2?.addresses ?? []
      resolve(
        items.map(item => ({
          name: item.roadAddress || item.jibunAddress || query,
          lat: parseFloat(item.y),
          lng: parseFloat(item.x),
        }))
      )
    })
  })
}
