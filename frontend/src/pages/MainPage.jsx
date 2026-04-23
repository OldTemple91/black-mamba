import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import NaverMap from '../components/map/NaverMap'
import MobilitySelector from '../components/search/MobilitySelector'
import ThemeToggle from '../components/common/ThemeToggle'

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

const WEATHER_OPTIONS = [
  { key: '',     label: '기본',  emoji: '☁️', hint: '날씨 영향 반영 안 함' },
  { key: 'RAIN', label: '비',    emoji: '🌧',  hint: '공유 자전거·장거리 도보 감점' },
  { key: 'SNOW', label: '눈',    emoji: '❄️', hint: '공유 모빌리티 강한 감점' },
  { key: 'HEAT', label: '폭염',  emoji: '☀️', hint: '장거리 도보 감점' },
  { key: 'COLD', label: '혹한',  emoji: '🥶', hint: '장거리 도보 감점' },
]

export default function MainPage() {
  const [origin, setOrigin]           = useState('')
  const [destination, setDestination] = useState('')
  const [originCoord, setOriginCoord]     = useState(null)
  const [destCoord, setDestCoord]         = useState(null)
  const [mobility, setMobility]       = useState([])
  const [searchMode, setSearchMode]   = useState('OPTIMAL')
  const [recommendationPreference, setRecommendationPreference] = useState('RELIABILITY')
  const [weather, setWeather]         = useState('')
  const [mapMode, setMapMode]         = useState(null)

  const [suggestions, setSuggestions]         = useState([])
  const [activeSuggestField, setActiveSuggestField] = useState(null)

  const navigate   = useNavigate()
  const debounceRef = useRef(null)

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

  useEffect(() => {
    const handler = () => {
      setSuggestions([])
      setActiveSuggestField(null)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

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

  const swapLocations = () => {
    setOrigin(destination)
    setDestination(origin)
    setOriginCoord(destCoord)
    setDestCoord(originCoord)
  }

  const handleSearch = () => {
    const originParam = originCoord
      ? `${originCoord.lat.toFixed(6)},${originCoord.lng.toFixed(6)}`
      : origin
    const destParam = destCoord
      ? `${destCoord.lat.toFixed(6)},${destCoord.lng.toFixed(6)}`
      : destination
    const weatherParam = weather ? `&weather=${weather}` : ''
    navigate(
      `/routes?origin=${encodeURIComponent(originParam)}&dest=${encodeURIComponent(destParam)}` +
      `&mobility=${mobility.join(',')}&searchMode=${searchMode}&recommendationPreference=${recommendationPreference}${weatherParam}`
    )
  }

  const canSearch = !!origin && !!destination

  return (
    <div className="min-h-screen">
      {/* ────────────────────────────────────────────────────────────
          데스크톱: 좌 검색 패널 sticky / 우 지도 full-height (lg+)
          모바일: stacked vertical
          ──────────────────────────────────────────────────────────── */}
      <div className="mx-auto max-w-7xl px-4 py-6 lg:py-10
                      lg:grid lg:grid-cols-[minmax(440px,520px)_1fr] lg:gap-8">

        {/* ──────── 좌측: Hero + 검색 패널 ──────── */}
        <div className="lg:sticky lg:top-10 lg:h-fit space-y-6">
          {/* Hero */}
          <div className="animate-hero-in">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                <div className="flex h-11 w-11 items-center justify-center rounded-2xl
                                bg-gradient-to-br from-blue-500 via-indigo-500 to-purple-500
                                shadow-lg shadow-indigo-500/30 animate-float-y">
                  <span className="text-2xl">🐍</span>
                </div>
                <div>
                  <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-50 tracking-tight">
                    Black Mamba
                  </h1>
                  <p className="text-[11px] text-slate-500 dark:text-slate-400 tracking-wider uppercase">
                    MaaS Routing Engine
                  </p>
                </div>
              </div>
              <ThemeToggle />
            </div>
            <p className="mt-4 text-[15px] text-slate-600 dark:text-slate-300 leading-relaxed">
              대중교통 + 공공자전거 + 개인 이동수단을 결합해
              <br />
              <span className="font-semibold text-slate-800 dark:text-slate-100">
                "자가용 대체 가능한 경로"
              </span>
              를 설명 가능하게 추천합니다.
            </p>
          </div>

          {/* ──────── 검색 패널 (Glass) ──────── */}
          <div className="glass-panel rounded-3xl p-5 shadow-xl shadow-slate-200/50
                          dark:shadow-slate-950/40 animate-hero-in animate-hero-in-delay-1">
            {/* 출발/목적지 입력 — 세로 스택 + 중간 swap 버튼 */}
            <div className="relative space-y-2" onMouseDown={e => e.stopPropagation()}>
              {/* 출발지 */}
              <div className="relative">
                <div className="flex items-center gap-2 rounded-2xl border border-slate-200 dark:border-slate-700
                                bg-white/80 dark:bg-slate-800/60 px-3 py-2.5 transition
                                focus-within:border-blue-400 focus-within:shadow-md focus-within:shadow-blue-500/10">
                  <span className="flex h-7 w-7 shrink-0 items-center justify-center
                                   rounded-full bg-blue-100 text-sm">🟢</span>
                  <input
                    value={origin}
                    onChange={handleOriginChange}
                    onFocus={() => { setActiveSuggestField('origin'); triggerSuggest(origin, 'origin') }}
                    placeholder="출발지 · 주소 · 좌표"
                    className="flex-1 bg-transparent text-[14px] text-slate-800 dark:text-slate-100
                               placeholder-slate-400 dark:placeholder-slate-500 focus:outline-none"
                  />
                  <button
                    onClick={() => toggleMapMode('origin')}
                    title="지도에서 출발지 선택"
                    className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full
                                text-[13px] transition ${
                      mapMode === 'origin'
                        ? 'bg-blue-500 text-white shadow'
                        : 'text-slate-400 hover:bg-slate-100'
                    }`}
                  >
                    📍
                  </button>
                </div>
                {activeSuggestField === 'origin' && suggestions.length > 0 && (
                  <ul className="absolute z-20 left-0 right-0 mt-1.5 overflow-hidden
                                 rounded-2xl border border-slate-200 dark:border-slate-700
                                 bg-white dark:bg-slate-800 shadow-xl">
                    {suggestions.map((item, idx) => (
                      <li
                        key={idx}
                        onMouseDown={() => selectSuggestion(item, 'origin')}
                        className="cursor-pointer border-b border-slate-100 dark:border-slate-700 px-3 py-2
                                   text-sm text-slate-700 dark:text-slate-200
                                   last:border-b-0 hover:bg-blue-50 dark:hover:bg-slate-700"
                      >
                        📍 {item.name}
                      </li>
                    ))}
                  </ul>
                )}
              </div>

              {/* Swap 버튼 (중앙) */}
              <div className="flex justify-center -my-1">
                <button
                  onClick={swapLocations}
                  disabled={!origin && !destination}
                  title="출발/도착 교체"
                  className="flex h-7 w-7 items-center justify-center rounded-full
                             border border-slate-200 bg-white text-slate-500 shadow-sm
                             transition hover:rotate-180 hover:text-blue-500 disabled:opacity-30"
                >
                  ⇅
                </button>
              </div>

              {/* 목적지 */}
              <div className="relative">
                <div className="flex items-center gap-2 rounded-2xl border border-slate-200 dark:border-slate-700
                                bg-white/80 dark:bg-slate-800/60 px-3 py-2.5 transition
                                focus-within:border-blue-400 focus-within:shadow-md focus-within:shadow-blue-500/10">
                  <span className="flex h-7 w-7 shrink-0 items-center justify-center
                                   rounded-full bg-rose-100 text-sm">🔴</span>
                  <input
                    value={destination}
                    onChange={handleDestChange}
                    onFocus={() => { setActiveSuggestField('destination'); triggerSuggest(destination, 'destination') }}
                    placeholder="목적지 · 주소 · 좌표"
                    className="flex-1 bg-transparent text-[14px] text-slate-800 dark:text-slate-100
                               placeholder-slate-400 dark:placeholder-slate-500 focus:outline-none"
                  />
                  <button
                    onClick={() => toggleMapMode('destination')}
                    title="지도에서 목적지 선택"
                    className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full
                                text-[13px] transition ${
                      mapMode === 'destination'
                        ? 'bg-rose-500 text-white shadow'
                        : 'text-slate-400 hover:bg-slate-100'
                    }`}
                  >
                    📍
                  </button>
                </div>
                {activeSuggestField === 'destination' && suggestions.length > 0 && (
                  <ul className="absolute z-20 left-0 right-0 mt-1.5 overflow-hidden
                                 rounded-2xl border border-slate-200 bg-white shadow-xl">
                    {suggestions.map((item, idx) => (
                      <li
                        key={idx}
                        onMouseDown={() => selectSuggestion(item, 'destination')}
                        className="cursor-pointer border-b border-slate-100 px-3 py-2 text-sm text-slate-700
                                   last:border-b-0 hover:bg-blue-50"
                      >
                        📍 {item.name}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>

            {/* MobilitySelector */}
            <div className="mt-4">
              <MobilitySelector
                selected={mobility}
                onChange={setMobility}
                searchMode={searchMode}
                onSearchModeChange={setSearchMode}
                recommendationPreference={recommendationPreference}
                onRecommendationPreferenceChange={setRecommendationPreference}
              />
            </div>

            {/* 날씨 옵션 */}
            <div className="mt-4 rounded-2xl bg-slate-50/70 dark:bg-slate-800/50 p-3">
              <p className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
                ☁️ 날씨 (선택)
              </p>
              <div className="flex flex-wrap gap-1.5">
                {WEATHER_OPTIONS.map(opt => (
                  <button
                    key={opt.key}
                    type="button"
                    onClick={() => setWeather(opt.key)}
                    title={opt.hint}
                    className={`flex items-center gap-1 rounded-full border px-3 py-1.5 text-xs font-medium transition ${
                      weather === opt.key
                        ? 'border-blue-500 bg-blue-500 text-white shadow-sm shadow-blue-500/30'
                        : 'border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:border-slate-300 dark:hover:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700'
                    }`}
                  >
                    <span>{opt.emoji}</span>
                    <span>{opt.label}</span>
                  </button>
                ))}
              </div>
              {weather && (
                <p className="mt-2 text-[11px] text-slate-500 dark:text-slate-400">
                  ✨ {WEATHER_OPTIONS.find(o => o.key === weather)?.hint}
                </p>
              )}
            </div>

            {/* 검색 버튼 */}
            <button
              onClick={handleSearch}
              disabled={!canSearch}
              className={`mt-5 w-full rounded-2xl py-3 text-sm font-semibold text-white transition
                          ${canSearch
                            ? 'bg-gradient-to-r from-blue-500 via-indigo-500 to-purple-500 hover:brightness-110 active:scale-[0.99] animate-gentle-pulse'
                            : 'bg-slate-300 dark:bg-slate-700 dark:text-slate-400 cursor-not-allowed'}`}
            >
              {canSearch ? '🚀 경로 탐색' : '출발지 / 목적지를 입력하세요'}
            </button>
          </div>

          {/* 하단 요약 통계 */}
          <div className="grid grid-cols-3 gap-2 text-center animate-hero-in animate-hero-in-delay-2">
            {[
              { label: '관측 축',       value: '4',   suffix: '축' },
              { label: '자체 알고리즘', value: '8',   suffix: '종' },
              { label: 'Core 개선',     value: '14',  suffix: '건' },
            ].map(s => (
              <div key={s.label}
                   className="rounded-2xl border border-slate-200 dark:border-slate-700
                              bg-white/70 dark:bg-slate-800/50 px-3 py-2">
                <div className="text-[11px] uppercase tracking-wider text-slate-500 dark:text-slate-400">
                  {s.label}
                </div>
                <div className="mt-0.5 text-xl font-bold text-slate-800 dark:text-slate-100">
                  {s.value}
                  <span className="ml-0.5 text-xs font-normal text-slate-400 dark:text-slate-500">
                    {s.suffix}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* ──────── 우측: 지도 (데스크톱 sticky) ──────── */}
        <div className="mt-6 lg:mt-0 animate-hero-in animate-hero-in-delay-3">
          <div className="map-container overflow-hidden rounded-3xl border border-slate-200 dark:border-slate-700
                          bg-white/70 dark:bg-slate-800/50
                          lg:sticky lg:top-10 lg:h-[calc(100vh-5rem)]">
            <div className="flex items-center justify-between border-b border-slate-100 dark:border-slate-700
                            bg-white/80 dark:bg-slate-900/70 px-4 py-2.5 backdrop-blur">
              <p className="text-xs font-semibold text-slate-600 dark:text-slate-300">
                {mapMode === 'origin'  ? '🟢 지도에서 출발지를 클릭하세요' :
                 mapMode === 'destination' ? '🔴 지도에서 목적지를 클릭하세요' :
                 '🗺 서울 지하철 + 버스 노선'}
              </p>
              <p className="text-[10px] text-slate-400 dark:text-slate-500">Naver Maps</p>
            </div>
            <div className="h-[400px] lg:h-full">
              <NaverMap onMapClick={handleMapClick} mapMode={mapMode} />
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
