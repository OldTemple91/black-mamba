import axios from 'axios'

const BASE_URL = 'http://localhost:8080/api'

export const searchRoutes = async ({ originLat, originLng, destLat, destLng, mobility }) => {
  const { data } = await axios.get(`${BASE_URL}/routes`, {
    params: { originLat, originLng, destLat, destLng, mobility: mobility.join(',') }
  })
  return data.routes
}
