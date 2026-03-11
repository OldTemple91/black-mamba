import axios from 'axios'

// 개발: Vite 프록시(/api → localhost:8081), 프로덕션: 실제 API 서버
const BASE_URL = '/api'

export const searchRoutes = async ({ originLat, originLng, destLat, destLng, mobility, searchMode = 'SPECIFIC' }) => {
  const { data } = await axios.get(`${BASE_URL}/routes`, {
    params: {
      originLat, originLng, destLat, destLng,
      mobility: mobility.join(','),
      searchMode
    }
  })
  return data.routes
}
