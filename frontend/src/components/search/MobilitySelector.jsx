const MOBILITY_OPTIONS = [
  { id: 'DDAREUNGI',          label: '🚲 따릉이',       unavailable: false },
  { id: 'PERSONAL_EBIKE',     label: '⚡ 전기자전거',    unavailable: false },
  { id: 'PERSONAL_KICKBOARD', label: '🛴 개인킥보드',   unavailable: false },
  { id: 'KICKBOARD_SHARED',   label: '🛴 공유킥보드',   unavailable: true  },
]

const OPTIMAL_ID = 'OPTIMAL'

const PREFERENCE_OPTIONS = [
  { id: 'RELIABILITY', label: '🛡️ 신뢰도 우선', description: '가용성, 접근 도보, 환승 마찰을 더 엄격하게 반영합니다.' },
  { id: 'TIME_PRIORITY', label: '⚡ 시간 우선', description: '빠른 경로를 더 적극적으로 추천합니다.' },
]

export default function MobilitySelector({
  selected,
  onChange,
  searchMode,
  onSearchModeChange,
  recommendationPreference,
  onRecommendationPreferenceChange,
}) {
  const isOptimal = searchMode === OPTIMAL_ID

  const toggleOptimal = () => {
    if (isOptimal) {
      onSearchModeChange('SPECIFIC')
    } else {
      onChange([])                // 기존 선택 해제
      onSearchModeChange(OPTIMAL_ID)
    }
  }

  const toggle = (id) => {
    onSearchModeChange('SPECIFIC')  // 일반 버튼 누르면 SPECIFIC
    onChange(selected.includes(id)
      ? selected.filter(s => s !== id)
      : [...selected, id])
  }

  return (
    <div className="space-y-3">
      <div className="flex gap-2 flex-wrap">
        {MOBILITY_OPTIONS.map(opt => (
          <button
            key={opt.id}
            onClick={() => !opt.unavailable && toggle(opt.id)}
            disabled={isOptimal || opt.unavailable}
            title={opt.unavailable ? '현재 데이터 미제공' : undefined}
            className={`px-3 py-1 rounded-full border text-sm transition
              ${isOptimal || opt.unavailable
                ? 'opacity-40 cursor-not-allowed bg-white dark:bg-slate-800 text-gray-400 dark:text-slate-500 border-gray-200 dark:border-slate-700'
                : selected.includes(opt.id)
                  ? 'bg-blue-500 text-white border-blue-500'
                  : 'bg-white dark:bg-slate-800 text-gray-600 dark:text-slate-300 border-gray-300 dark:border-slate-600'}`}
          >
            {opt.label}{opt.unavailable ? ' (준비중)' : ''}
          </button>
        ))}
        <button
          onClick={toggleOptimal}
          className={`px-3 py-1 rounded-full border text-sm transition
            ${isOptimal
              ? 'bg-purple-500 text-white border-purple-500'
              : 'bg-white dark:bg-slate-800 text-purple-600 dark:text-purple-300 border-purple-300 dark:border-purple-800/60'}`}
        >
          🔍 최적 탐색
        </button>
      </div>

      <div className="rounded-2xl border border-slate-200 dark:border-slate-700
                      bg-slate-50 dark:bg-slate-800/50 px-3 py-3">
        <p className="text-[11px] font-semibold uppercase tracking-[0.18em]
                      text-slate-400 dark:text-slate-500">Recommendation Preference</p>
        <div className="mt-2 grid grid-cols-2 gap-2">
          {PREFERENCE_OPTIONS.map(option => {
            const isSelected = recommendationPreference === option.id
            return (
              <button
                key={option.id}
                type="button"
                onClick={() => onRecommendationPreferenceChange(option.id)}
                className={`rounded-xl border px-3 py-3 text-left transition ${
                  isSelected
                    ? 'border-slate-900 dark:border-slate-200 bg-slate-900 dark:bg-slate-200 text-white dark:text-slate-900 shadow-sm'
                    : 'border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900/60 text-slate-700 dark:text-slate-200 hover:border-slate-300 dark:hover:border-slate-600'
                }`}
                title={option.description}
              >
                <p className="text-sm font-semibold">{option.label}</p>
                <p className={`mt-1 text-[11px] leading-5 ${
                  isSelected
                    ? 'text-slate-200 dark:text-slate-600'
                    : 'text-slate-500 dark:text-slate-400'
                }`}>
                  {option.description}
                </p>
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}
