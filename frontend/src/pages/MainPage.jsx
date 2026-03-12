import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import NaverMap from '../components/map/NaverMap'
import MobilitySelector from '../components/search/MobilitySelector'

// 자동완성: 네이버 지역 검색 API (POI 키워드 지원 — "강남역", "홍대입구" 등)
async function fetchSuggestions(query) {
  if (!query || query.length < 2) return []
  try {
    const res = await fetch(`/api/places?query=${encodeURIComponent(query)}`)
    if (!res.ok) return []
    return await res.json()  // [{ name, lat, lng }]
  } catch {
    return []
  }
}

export default function MainPage() {
  const [origin, setOrigin]           = useState('')
  const [destination, setDestination] = useState('')
  const [originCoord, setOriginCoord]     = useState(null)  // { lat, lng } or null
  const [destCoord, setDestCoord]         = useState(null)
  const [mobility, setMobility]       = useState([])
  const [searchMode, setSearchMode]   = useState('OPTIMAL')
  const [recommendationPreference, setRecommendationPreference] = useState('RELIABILITY')
  const [mapMode, setMapMode]         = useState(null)  // 'origin' | 'destination' | null

  // 자동완성
  const [suggestions, setSuggestions]         = useState([])
  const [activeSuggestField, setActiveSuggestField] = useState(null)  // 'origin' | 'destination' | null

  const navigate   = useNavigate()
  const debounceRef = useRef(null)

  // 입력 변경 시 좌표 초기화 + 연관검색어 디바운스
  const handleOriginChange = (e) => {
    const val = e.target.value
    setOrigin(val)
    setOriginCoord(null)
    setActiveSuggestField('origin')
    triggerSuggest(val, 'origin')
  }

  const handleDestChange = (e) => {
    const val = e.target.value
    setDestination(val)
    setDestCoord(null)
    setActiveSuggestField('destination')
    triggerSuggest(val, 'destination')
  }

  const triggerSuggest = (query, field) => {
    clearTimeout(debounceRef.current)
    if (!query || query.length < 2) {
      setSuggestions([])
      return
    }
    debounceRef.current = setTimeout(async () => {
      const items = await fetchSuggestions(query)
      setSuggestions(items)
      setActiveSuggestField(field)
    }, 300)
  }

  // 연관검색어 항목 선택
  const selectSuggestion = (item, field) => {
    if (field === 'origin') {
      setOrigin(item.name)
      setOriginCoord({ lat: item.lat, lng: item.lng })
    } else {
      setDestination(item.name)
      setDestCoord({ lat: item.lat, lng: item.lng })
    }
    setSuggestions([])
    setActiveSuggestField(null)
  }

  // 드롭다운 외부 클릭 시 닫기
  useEffect(() => {
    const handler = () => {
      setSuggestions([])
      setActiveSuggestField(null)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  // 지도 클릭 핸들러
  const handleMapClick = useCallback(({ lat, lng }) => {
    const coord = `${lat.toFixed(6)},${lng.toFixed(6)}`
    if (mapMode === 'origin') {
      setOrigin(coord)
      setOriginCoord({ lat, lng })
    } else if (mapMode === 'destination') {
      setDestination(coord)
      setDestCoord({ lat, lng })
    }
    setMapMode(null)
    setSuggestions([])
  }, [mapMode])

  const toggleMapMode = (field) => {
    setMapMode(prev => (prev === field ? null : field))
    setSuggestions([])
  }

  const handleSearch = () => {
    // 좌표가 이미 있으면 "lat,lng" 형식으로, 없으면 입력 텍스트 그대로 전달
    const originParam = originCoord
      ? `${originCoord.lat.toFixed(6)},${originCoord.lng.toFixed(6)}`
      : origin
    const destParam = destCoord
      ? `${destCoord.lat.toFixed(6)},${destCoord.lng.toFixed(6)}`
      : destination

    navigate(
      `/routes?origin=${encodeURIComponent(originParam)}&dest=${encodeURIComponent(destParam)}` +
      `&mobility=${mobility.join(',')}&searchMode=${searchMode}&recommendationPreference=${recommendationPreference}`
    )
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-lg mx-auto p-4">
        <h1 className="text-2xl font-bold text-gray-800 mb-4">
          🐍 Black Mamba
        </h1>
        <div className="bg-white rounded-xl shadow p-4 space-y-3">

          {/* 출발지 */}
          <div className="relative" onMouseDown={e => e.stopPropagation()}>
            <div className="flex gap-2">
              <input
                value={origin}
                onChange={handleOriginChange}
                onFocus={() => { setActiveSuggestField('origin'); triggerSuggest(origin, 'origin') }}
                placeholder="출발지를 입력하세요"
                className="flex-1 border rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
              />
              <button
                onClick={() => toggleMapMode('origin')}
                title="지도에서 출발지 선택"
                className={`px-3 py-2 rounded-lg border text-sm transition ${
                  mapMode === 'origin'
                    ? 'bg-blue-500 text-white border-blue-500'
                    : 'bg-white text-gray-500 border-gray-300 hover:border-blue-400'
                }`}
              >
                📍
              </button>
            </div>
            {activeSuggestField === 'origin' && suggestions.length > 0 && (
              <ul className="absolute z-10 left-0 right-0 mt-1 bg-white border border-gray-200 rounded-lg shadow-lg overflow-hidden">
                {suggestions.map((item, idx) => (
                  <li
                    key={idx}
                    onMouseDown={() => selectSuggestion(item, 'origin')}
                    className="px-3 py-2 text-sm text-gray-700 hover:bg-blue-50 cursor-pointer border-b last:border-b-0"
                  >
                    📍 {item.name}
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* 목적지 */}
          <div className="relative" onMouseDown={e => e.stopPropagation()}>
            <div className="flex gap-2">
              <input
                value={destination}
                onChange={handleDestChange}
                onFocus={() => { setActiveSuggestField('destination'); triggerSuggest(destination, 'destination') }}
                placeholder="목적지를 입력하세요"
                className="flex-1 border rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
              />
              <button
                onClick={() => toggleMapMode('destination')}
                title="지도에서 목적지 선택"
                className={`px-3 py-2 rounded-lg border text-sm transition ${
                  mapMode === 'destination'
                    ? 'bg-blue-500 text-white border-blue-500'
                    : 'bg-white text-gray-500 border-gray-300 hover:border-blue-400'
                }`}
              >
                📍
              </button>
            </div>
            {activeSuggestField === 'destination' && suggestions.length > 0 && (
              <ul className="absolute z-10 left-0 right-0 mt-1 bg-white border border-gray-200 rounded-lg shadow-lg overflow-hidden">
                {suggestions.map((item, idx) => (
                  <li
                    key={idx}
                    onMouseDown={() => selectSuggestion(item, 'destination')}
                    className="px-3 py-2 text-sm text-gray-700 hover:bg-blue-50 cursor-pointer border-b last:border-b-0"
                  >
                    📍 {item.name}
                  </li>
                ))}
              </ul>
            )}
          </div>

          <MobilitySelector
            selected={mobility}
            onChange={setMobility}
            searchMode={searchMode}
            onSearchModeChange={setSearchMode}
            recommendationPreference={recommendationPreference}
            onRecommendationPreferenceChange={setRecommendationPreference}
          />
          <button
            onClick={handleSearch}
            disabled={!origin && !destination}
            className="w-full bg-blue-500 text-white py-2 rounded-lg font-medium hover:bg-blue-600 transition disabled:opacity-40 disabled:cursor-not-allowed"
          >
            경로 탐색
          </button>
        </div>

        {/* 지도 */}
        <div className="mt-4">
          <NaverMap onMapClick={handleMapClick} mapMode={mapMode} />
        </div>
      </div>
    </div>
  )
}
