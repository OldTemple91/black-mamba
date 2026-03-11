// KICKBOARD_SHARED: TAGO API 서울 데이터 미제공으로 실데이터 없음 (B-3)
// → unavailable 플래그로 표시만 하고 선택 불가 처리
const MOBILITY_OPTIONS = [
  { id: 'DDAREUNGI',        label: '🚲 따릉이',    unavailable: false },
  { id: 'KICKBOARD_SHARED', label: '🛴 공유킥보드', unavailable: true  },
  { id: 'PERSONAL',         label: '🛴 개인킥보드', unavailable: false },
]

const OPTIMAL_ID = 'OPTIMAL'

export default function MobilitySelector({ selected, onChange, searchMode, onSearchModeChange }) {
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
    <div className="flex gap-2 flex-wrap">
      {MOBILITY_OPTIONS.map(opt => (
        <button
          key={opt.id}
          onClick={() => !opt.unavailable && toggle(opt.id)}
          disabled={isOptimal || opt.unavailable}
          title={opt.unavailable ? '현재 데이터 미제공' : undefined}
          className={`px-3 py-1 rounded-full border text-sm transition
            ${isOptimal || opt.unavailable
              ? 'opacity-40 cursor-not-allowed bg-white text-gray-400 border-gray-200'
              : selected.includes(opt.id)
                ? 'bg-blue-500 text-white border-blue-500'
                : 'bg-white text-gray-600 border-gray-300'}`}
        >
          {opt.label}{opt.unavailable ? ' (준비중)' : ''}
        </button>
      ))}
      {/* 최적 탐색 버튼 */}
      <button
        onClick={toggleOptimal}
        className={`px-3 py-1 rounded-full border text-sm transition
          ${isOptimal
            ? 'bg-purple-500 text-white border-purple-500'
            : 'bg-white text-purple-600 border-purple-300'}`}
      >
        🔍 최적 탐색
      </button>
    </div>
  )
}
