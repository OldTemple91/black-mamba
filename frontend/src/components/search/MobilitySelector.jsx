const MOBILITY_OPTIONS = [
  { id: 'DDAREUNGI', label: '🚲 따릉이' },
  { id: 'KICKBOARD_SHARED', label: '🛴 킥보드' },
  { id: 'PERSONAL', label: '🚴 개인 자전거' },
]

export default function MobilitySelector({ selected, onChange }) {
  const toggle = (id) => {
    onChange(selected.includes(id)
      ? selected.filter(s => s !== id)
      : [...selected, id])
  }

  return (
    <div className="flex gap-2 flex-wrap">
      {MOBILITY_OPTIONS.map(opt => (
        <button
          key={opt.id}
          onClick={() => toggle(opt.id)}
          className={`px-3 py-1 rounded-full border text-sm
            ${selected.includes(opt.id)
              ? 'bg-blue-500 text-white border-blue-500'
              : 'bg-white text-gray-600 border-gray-300'}`}
        >
          {opt.label}
        </button>
      ))}
    </div>
  )
}
