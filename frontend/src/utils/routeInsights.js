const RISK_STYLES = {
  stable: 'bg-emerald-50 text-emerald-700 border border-emerald-200',
  caution: 'bg-amber-50 text-amber-700 border border-amber-200',
  risk: 'bg-rose-50 text-rose-700 border border-rose-200',
  info: 'bg-slate-50 text-slate-700 border border-slate-200',
}

function sumDistanceByType(route, type) {
  return route.legs
    .filter(leg => leg.type === type)
    .reduce((total, leg) => total + (leg.distanceMeters || 0), 0)
}

export function countTransfers(route) {
  const transitLegs = route.legs.filter(leg => leg.type === 'TRANSIT').length
  return Math.max(transitLegs - 1, 0)
}

export function getWalkingDistance(route) {
  return sumDistanceByType(route, 'WALK')
}

export function getMobilityDistance(route) {
  return ['BIKE', 'KICKBOARD']
    .flatMap(type => route.legs.filter(leg => leg.type === type))
    .reduce((total, leg) => total + (leg.distanceMeters || 0), 0)
}

export function getCostBreakdown(route) {
  const items = route.costBreakdown?.items
  if (Array.isArray(items) && items.length > 0) {
    return items.filter(item => (item.amountWon || 0) > 0)
  }

  if ((route.totalCostWon || 0) <= 0) {
    return []
  }

  return [{ label: '예상 비용', amountWon: route.totalCostWon }]
}

export function getHubSummary(route) {
  const hubs = route.evaluation?.hubs
  if (!Array.isArray(hubs) || hubs.length === 0) {
    return []
  }

  return hubs.slice(0, 4).map(hub => ({
    label: hubTypeLabel(hub.type),
    detail: `${hub.name} · ${hubRoleLabel(hub.role)}`,
    source: hub.source,
    metadata: hub.metadata ?? {},
    tone: hub.source === 'selected-candidate' ? 'candidate' : 'actual',
  }))
}

export function getGenerationDiagnostics(route) {
  const diagnostics = route.insights?.generationDiagnostics
  if (!Array.isArray(diagnostics) || diagnostics.length === 0) {
    return []
  }

  return diagnostics.slice(0, 3).map(item => ({
    phase: item.phase,
    mobilityType: item.mobilityType,
    reasonCode: item.reasonCode,
    candidateCount: item.candidateCount,
    message: item.message,
    tone: inferDiagnosticTone(item.reasonCode, item.message),
  }))
}

export function getFallbackDiagnostics(route) {
  const diagnostics = route.insights?.fallbackDiagnostics
  if (!Array.isArray(diagnostics) || diagnostics.length === 0) {
    return []
  }

  return diagnostics.slice(0, 3).map(message => ({
    message,
    tone: inferFallbackTone(message),
  }))
}

export function findBaselineRoute(routes) {
  return routes.find(route => route.type === 'TRANSIT_ONLY') ?? routes[0] ?? null
}

export function getRecommendationReasons(route, baselineRoute) {
  const backendReasons = route.insights?.recommendationReasons
  if (Array.isArray(backendReasons) && backendReasons.length > 0) {
    return backendReasons.slice(0, 3)
  }

  const reasons = []
  const baselineMinutes = route.comparison?.originalMinutes ?? baselineRoute?.totalMinutes ?? route.totalMinutes
  const baselineWalking = baselineRoute ? getWalkingDistance(baselineRoute) : null
  const baselineTransfers = baselineRoute ? countTransfers(baselineRoute) : null

  if ((route.comparison?.savedMinutes ?? 0) > 0) {
    reasons.push(`대중교통 대비 ${route.comparison.savedMinutes}분 단축`)
  } else if (baselineMinutes > route.totalMinutes) {
    reasons.push(`기준 경로보다 ${baselineMinutes - route.totalMinutes}분 빠름`)
  }

  if (baselineWalking != null) {
    const walkDelta = baselineWalking - getWalkingDistance(route)
    if (walkDelta >= 150) reasons.push(`도보 ${walkDelta}m 감소`)
  }

  if (baselineTransfers != null) {
    const transferDelta = baselineTransfers - countTransfers(route)
    if (transferDelta > 0) reasons.push(`환승 ${transferDelta}회 감소`)
  }

  const bikeLeg = route.legs.find(leg => leg.type === 'BIKE')
  if (bikeLeg?.mobilityInfo?.dropoffStationName) {
    reasons.push('반납 정류소 확인')
  }

  const kickboardLeg = route.legs.find(leg => leg.type === 'KICKBOARD')
  if (kickboardLeg?.mobilityInfo?.batteryLevel >= 60) {
    reasons.push('배터리 여유 확보')
  }

  if (reasons.length === 0) {
    reasons.push(route.recommended ? '균형 잡힌 경로로 추천' : '비교 가능한 대안 경로')
  }

  return reasons.slice(0, 3)
}

export function getRiskBadges(route) {
  const backendBadges = route.insights?.riskBadges
  if (Array.isArray(backendBadges) && backendBadges.length > 0) {
    return backendBadges.slice(0, 3).map(label => ({
      label,
      className: inferRiskStyle(label),
    }))
  }

  const badges = []
  const walkingMeters = getWalkingDistance(route)
  const kickboardLegs = route.legs.filter(leg => leg.type === 'KICKBOARD')
  const bikeLegs = route.legs.filter(leg => leg.type === 'BIKE')
  const sharedLeg = route.legs.find(leg =>
    (leg.type === 'BIKE' || leg.type === 'KICKBOARD') &&
    leg.mobilityInfo?.operatorName &&
    leg.mobilityInfo.operatorName !== '개인'
  )

  if (kickboardLegs.some(leg => (leg.mobilityInfo?.batteryLevel ?? 100) < 30)) {
    badges.push({ label: '배터리 낮음', tone: 'risk' })
  }
  if (sharedLeg) {
    badges.push({ label: '공유수단 의존', tone: 'caution' })
  }
  if (bikeLegs.some(leg => !leg.mobilityInfo?.dropoffStationName)) {
    badges.push({ label: '반납 정보 약함', tone: 'risk' })
  }
  if (bikeLegs.some(leg => (leg.mobilityInfo?.availableCount ?? 0) <= 2)) {
    badges.push({ label: '대여 여유 적음', tone: 'caution' })
  }
  if (walkingMeters >= 800) {
    badges.push({ label: '도보 부담', tone: 'caution' })
  }
  if (countTransfers(route) >= 2) {
    badges.push({ label: '환승 많음', tone: 'caution' })
  }
  if (badges.length === 0) {
    badges.push({ label: '안정적', tone: 'stable' })
  }

  return badges.slice(0, 3).map(badge => ({ ...badge, className: RISK_STYLES[badge.tone] ?? RISK_STYLES.info }))
}

function inferRiskStyle(label) {
  if (label === '안정적') return RISK_STYLES.stable
  if (label === '배터리 낮음' || label === '반납 정보 약함') return RISK_STYLES.risk
  if (label === '공유수단 의존' || label === '대여 여유 적음' || label === '도보 부담' || label === '환승 많음' || label === '접근 도보 김') {
    return RISK_STYLES.caution
  }
  return RISK_STYLES.info
}

function inferDiagnosticTone(reasonCode, message) {
  if (reasonCode === 'SAME_PICKUP_DROPOFF') return 'risk'
  if (reasonCode === 'NO_DROPOFF' || reasonCode === 'NO_PICKUP' || reasonCode === 'DIRECT_DISTANCE_EXCEEDED') return 'caution'
  if (message?.includes('동일 정류소')) return 'risk'
  if (message?.includes('반납 가능한 정류소') || message?.includes('대여 가능한 수단')) return 'caution'
  return 'info'
}

function inferFallbackTone(message) {
  if (message.includes('추정값')) return 'caution'
  if (message.includes('빈 결과')) return 'risk'
  if (message.includes('stale snapshot')) return 'caution'
  return 'info'
}

export function getTransferSummary(route) {
  return route.legs
    .map((leg, index) => {
      if (leg.type === 'TRANSIT') {
        const line = leg.transitInfo?.lineName ?? (leg.mode === 'SUBWAY' ? '지하철' : '버스')
        return `${leg.start?.name ?? `지점 ${index + 1}`}: ${line} 탑승`
      }
      if (leg.type === 'BIKE') {
        const station = leg.mobilityInfo?.stationName ?? leg.start?.name
        return `${station ?? `지점 ${index + 1}`}: 자전거 환승`
      }
      if (leg.type === 'KICKBOARD') {
        const provider = leg.mobilityInfo?.operatorName ?? '킥보드'
        return `${leg.start?.name ?? `지점 ${index + 1}`}: ${provider} 환승`
      }
      return null
    })
    .filter(Boolean)
    .slice(0, 3)
}

export function buildComparisonContext(routes) {
  if (routes.length === 0) {
    return {
      maxMinutes: 1,
      maxWalking: 1,
      maxCosts: 1,
      maxTransfers: 1,
    }
  }

  const minutes = routes.map(route => route.totalMinutes)
  const walking = routes.map(route => getWalkingDistance(route))
  const costs = routes.map(route => route.totalCostWon || 0)
  const transfers = routes.map(route => countTransfers(route))

  return {
    maxMinutes: Math.max(...minutes, 1),
    maxWalking: Math.max(...walking, 1),
    maxCosts: Math.max(...costs, 1),
    maxTransfers: Math.max(...transfers, 1),
  }
}

export function getComparisonBars(route, context) {
  const items = [
    { key: 'time', label: '시간', value: route.totalMinutes, max: context.maxMinutes, color: 'bg-blue-500', suffix: '분' },
    { key: 'walk', label: '도보', value: getWalkingDistance(route), max: context.maxWalking, color: 'bg-slate-500', suffix: 'm' },
    { key: 'cost', label: '비용', value: route.totalCostWon || 0, max: context.maxCosts, color: 'bg-amber-500', suffix: '원' },
    { key: 'transfer', label: '환승', value: countTransfers(route), max: context.maxTransfers, color: 'bg-emerald-500', suffix: '회' },
  ]

  return items.map(item => ({
    ...item,
    percent: Math.max(8, Math.round((item.value / item.max) * 100)),
  }))
}

export function getDebugFacts(route, baselineRoute, searchMode, recommendationPreference = 'RELIABILITY') {
  const bikeLegs = route.legs.filter(leg => leg.type === 'BIKE')
  const kickboardLegs = route.legs.filter(leg => leg.type === 'KICKBOARD')
  const transitLegs = route.legs.filter(leg => leg.type === 'TRANSIT')

  return [
    `탐색 모드: ${searchMode}`,
    `추천 성향: ${recommendationPreference === 'TIME_PRIORITY' ? '시간 우선' : '신뢰도 우선'}`,
    `경로 타입: ${route.type}`,
    `총 구간 수: ${route.legs.length}개`,
    `대중교통 구간: ${transitLegs.length}개 / 환승 ${countTransfers(route)}회`,
    `총 예상 비용: ${(route.totalCostWon || 0).toLocaleString()}원`,
    getCostBreakdown(route).length > 0
      ? `비용 구성: ${getCostBreakdown(route).map(item => `${item.label} ${item.amountWon.toLocaleString()}원`).join(' + ')}`
      : '비용 구성: 없음',
    route.evaluation ? `평가 점수: ${(route.evaluation.totalScore * 100).toFixed(1)} / 시간 ${(route.evaluation.timeScore * 100).toFixed(0)} / 신뢰도 ${(route.evaluation.reliabilityScore * 100).toFixed(0)}` : '평가 데이터 없음',
    route.evaluation ? `접근 도보 최대: ${route.evaluation.maxAccessWalkDistanceMeters}m / 총 도보: ${route.evaluation.walkingDistanceMeters}m` : null,
    route.evaluation?.hubs?.length > 0
      ? `허브: ${route.evaluation.hubs.map(hub => `${hub.name}(${hub.type})`).join(', ')}`
      : '허브 정보 없음',
    route.evaluation?.hubs?.some(hub => hub.source === 'selected-candidate')
      ? `선택 허브 metadata: ${route.evaluation.hubs
        .filter(hub => hub.source === 'selected-candidate')
        .map(hub => `${hub.name}[${hub.metadata?.selectionPhase ?? '-'} / ${hub.metadata?.preferredMobility ?? '-'}]`)
        .join(', ')}`
      : '선택 허브 metadata 없음',
    Array.isArray(route.insights?.fallbackDiagnostics) && route.insights.fallbackDiagnostics.length > 0
      ? `fallback: ${route.insights.fallbackDiagnostics.join(' / ')}`
      : 'fallback 진단 없음',
    `도보 거리: ${getWalkingDistance(route)}m`,
    `마이크로모빌리티 거리: ${getMobilityDistance(route)}m`,
    bikeLegs.length > 0
      ? `따릉이 반납 정류소 확인: ${bikeLegs.every(leg => !!leg.mobilityInfo?.dropoffStationName) ? '예' : '아니오'}`
      : '따릉이 구간 없음',
    kickboardLegs.length > 0
      ? `킥보드 배터리: ${kickboardLegs.map(leg => `${leg.mobilityInfo?.batteryLevel ?? 0}%`).join(', ')}`
      : '킥보드 구간 없음',
    ...(Array.isArray(route.insights?.generationDiagnostics) && route.insights.generationDiagnostics.length > 0
      ? route.insights.generationDiagnostics.map(item => `혼합 경로 미생성 [${item.phase ?? '-'} / ${item.reasonCode ?? '-'}]: ${item.message}`)
      : []),
    baselineRoute ? `기준 경로 시간: ${baselineRoute.totalMinutes}분` : '기준 경로 없음',
  ].filter(Boolean)
}

function hubTypeLabel(type) {
  switch (type) {
    case 'SUBWAY_STATION': return '지하철 허브'
    case 'BUS_STOP': return '버스 허브'
    case 'BIKE_STATION': return '자전거 허브'
    case 'CARSHARE_ZONE': return '카셰어 존'
    case 'CHARGING_STATION': return '충전 허브'
    default: return '환승 허브'
  }
}

function hubRoleLabel(role) {
  switch (role) {
    case 'TRANSIT_BOARDING': return '대중교통 승차'
    case 'TRANSIT_ALIGHTING': return '대중교통 하차'
    case 'BIKE_PICKUP': return '자전거 대여'
    case 'BIKE_DROPOFF': return '자전거 반납'
    case 'KICKBOARD_PICKUP': return '킥보드 탑승'
    case 'KICKBOARD_DROPOFF': return '킥보드 하차'
    case 'FIRST_MILE_CANDIDATE': return '퍼스트마일 후보'
    case 'LAST_MILE_CANDIDATE': return '라스트마일 후보'
    default: return '환승'
  }
}
