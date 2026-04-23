import { useDarkMode } from '../../hooks/useDarkMode'

/**
 * 우상단 부동 다크모드 토글 버튼.
 * - 호버 시 살짝 회전
 * - localStorage 저장으로 새로고침 후에도 유지
 */
export default function ThemeToggle({ className = '' }) {
  const [theme, toggle] = useDarkMode()
  const isDark = theme === 'dark'

  return (
    <button
      onClick={toggle}
      title={isDark ? '라이트 모드로 전환' : '다크 모드로 전환'}
      className={`flex h-9 w-9 items-center justify-center rounded-full border text-sm
                  transition-all duration-300 hover:scale-110 hover:rotate-12 ${
                  isDark
                    ? 'border-slate-700 bg-slate-800 text-amber-300 shadow-lg shadow-slate-900/60'
                    : 'border-slate-200 bg-white text-slate-700 shadow-sm hover:border-slate-300'
                  } ${className}`}
    >
      {isDark ? '☀️' : '🌙'}
    </button>
  )
}
