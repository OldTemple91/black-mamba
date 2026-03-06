import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import NaverMap from '../components/map/NaverMap'
import MobilitySelector from '../components/search/MobilitySelector'

export default function MainPage() {
  const [origin, setOrigin] = useState('')
  const [destination, setDestination] = useState('')
  const [mobility, setMobility] = useState([])
  const [searchMode, setSearchMode] = useState('SPECIFIC')
  const navigate = useNavigate()

  const handleSearch = () => {
    navigate(
      `/routes?origin=${origin}&dest=${destination}` +
      `&mobility=${mobility.join(',')}&searchMode=${searchMode}`
    )
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-lg mx-auto p-4">
        <h1 className="text-2xl font-bold text-gray-800 mb-4">
          🐍 Black Mamba
        </h1>
        <div className="bg-white rounded-xl shadow p-4 space-y-3">
          <input
            value={origin}
            onChange={e => setOrigin(e.target.value)}
            placeholder="출발지를 입력하세요"
            className="w-full border rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
          />
          <input
            value={destination}
            onChange={e => setDestination(e.target.value)}
            placeholder="목적지를 입력하세요"
            className="w-full border rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
          />
          <MobilitySelector
            selected={mobility}
            onChange={setMobility}
            searchMode={searchMode}
            onSearchModeChange={setSearchMode}
          />
          <button
            onClick={handleSearch}
            className="w-full bg-blue-500 text-white py-2 rounded-lg font-medium hover:bg-blue-600 transition"
          >
            경로 탐색
          </button>
        </div>
        <div className="mt-4">
          <NaverMap />
        </div>
      </div>
    </div>
  )
}
