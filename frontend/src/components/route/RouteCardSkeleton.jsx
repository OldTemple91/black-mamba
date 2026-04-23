/**
 * 경로 탐색 로딩 중 skeleton 카드.
 * 실제 RouteCard 의 구조를 그대로 흉내내어 레이아웃 shift 를 없앰.
 */
export default function RouteCardSkeleton() {
  return (
    <div className="rounded-2xl border border-slate-200 dark:border-slate-700
                    bg-white/80 dark:bg-slate-800/60 p-4 animate-pulse">
      {/* 헤더 영역 */}
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 flex-1 flex-col gap-2">
          <div className="flex gap-1.5">
            <div className="h-5 w-12 rounded-full bg-slate-200 dark:bg-slate-700" />
            <div className="h-5 w-20 rounded-full bg-slate-200 dark:bg-slate-700" />
          </div>
          <div className="h-4 w-3/4 rounded bg-slate-200 dark:bg-slate-700" />
        </div>
        <div className="shrink-0 space-y-2 text-right">
          <div className="ml-auto h-6 w-16 rounded bg-slate-200 dark:bg-slate-700" />
          <div className="ml-auto h-3 w-20 rounded bg-slate-200 dark:bg-slate-700" />
        </div>
      </div>

      {/* Timeline bar */}
      <div className="mt-3 h-7 w-full rounded-lg bg-slate-200 dark:bg-slate-700" />
      <div className="mt-1.5 flex justify-between">
        <div className="h-3 w-12 rounded bg-slate-200 dark:bg-slate-700" />
        <div className="h-3 w-24 rounded bg-slate-200 dark:bg-slate-700" />
      </div>

      {/* 메타 배지 */}
      <div className="mt-3 flex gap-2">
        <div className="h-5 w-20 rounded-full bg-slate-200 dark:bg-slate-700" />
        <div className="h-5 w-16 rounded-full bg-slate-200 dark:bg-slate-700" />
      </div>

      {/* Comparison bars */}
      <div className="mt-3 grid grid-cols-2 gap-2">
        <div className="h-14 rounded-lg bg-slate-200 dark:bg-slate-700" />
        <div className="h-14 rounded-lg bg-slate-200 dark:bg-slate-700" />
      </div>
    </div>
  )
}
