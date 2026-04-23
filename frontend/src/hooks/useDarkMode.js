import { useEffect, useState } from 'react'

/**
 * Dark Mode 토글 훅.
 *
 * 우선순위:
 * 1) localStorage 에 저장된 사용자 선택
 * 2) OS 선호도 (prefers-color-scheme)
 * 3) 기본값 'light'
 *
 * 토글 시 document.documentElement.classList 의 'dark' 를 토글하고 localStorage 동기화.
 */
export function useDarkMode() {
  const [theme, setTheme] = useState(() => {
    if (typeof window === 'undefined') return 'light'
    const saved = localStorage.getItem('bm:theme')
    if (saved === 'dark' || saved === 'light') return saved
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  })

  useEffect(() => {
    const root = document.documentElement
    if (theme === 'dark') {
      root.classList.add('dark')
    } else {
      root.classList.remove('dark')
    }
    localStorage.setItem('bm:theme', theme)
  }, [theme])

  const toggle = () => setTheme(prev => (prev === 'dark' ? 'light' : 'dark'))

  return [theme, toggle]
}
