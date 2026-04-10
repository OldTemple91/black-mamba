#!/bin/bash
# Black Mamba — 성능 측정 스크립트
# 사용법: ./scripts/perf-test.sh
#
# 사전 조건: 백엔드가 http://localhost:8081 에서 실행 중이어야 함

set -e

BASE_URL="http://localhost:8081"
RESULTS_FILE="output/perf-results-$(date +%Y%m%d-%H%M%S).md"
mkdir -p output

echo "# Black Mamba 성능 측정 결과" > "$RESULTS_FILE"
echo "" >> "$RESULTS_FILE"
echo "> 측정 시각: $(date '+%Y-%m-%d %H:%M:%S')" >> "$RESULTS_FILE"
echo "" >> "$RESULTS_FILE"

# 헬스체크
echo "=== 헬스체크 ==="
curl -sf "$BASE_URL/actuator/health" | python3 -m json.tool
echo ""

# 시나리오 정의
declare -a SCENARIOS=(
  "강남→홍대|37.4979|127.0276|37.5573|126.9246|OPTIMAL|RELIABILITY"
  "수서→이태원|37.4872|127.1016|37.5345|126.9946|OPTIMAL|TIME_PRIORITY"
  "여의도→서울숲|37.5264|126.9343|37.5445|127.0374|OPTIMAL|RELIABILITY"
)

echo "## API 응답 시간" >> "$RESULTS_FILE"
echo "" >> "$RESULTS_FILE"
echo "| 시나리오 | 모드 | 1회차(cold) | 2회차(warm) | 3회차(warm) | 경로 수 |" >> "$RESULTS_FILE"
echo "|---------|------|-----------|-----------|-----------|--------|" >> "$RESULTS_FILE"

for scenario in "${SCENARIOS[@]}"; do
  IFS='|' read -r name olat olng dlat dlng mode pref <<< "$scenario"

  URL="$BASE_URL/api/routes?originLat=$olat&originLng=$olng&destLat=$dlat&destLng=$dlng&searchMode=$mode&recommendationPreference=$pref"

  echo "=== $name ($mode/$pref) ==="

  # 3회 반복 측정
  TIMES=()
  ROUTE_COUNT=0
  for i in 1 2 3; do
    START=$(python3 -c "import time; print(int(time.time()*1000))")
    RESPONSE=$(curl -sf "$URL")
    END=$(python3 -c "import time; print(int(time.time()*1000))")
    ELAPSED=$((END - START))
    TIMES+=("${ELAPSED}ms")

    if [ $i -eq 1 ]; then
      ROUTE_COUNT=$(echo "$RESPONSE" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('routes',[])))" 2>/dev/null || echo "0")
    fi

    echo "  $i회차: ${ELAPSED}ms"
  done

  echo "| $name | $mode/$pref | ${TIMES[0]} | ${TIMES[1]} | ${TIMES[2]} | $ROUTE_COUNT |" >> "$RESULTS_FILE"
done

# 캐시 메트릭
echo "" >> "$RESULTS_FILE"
echo "## 캐시 메트릭" >> "$RESULTS_FILE"
echo "" >> "$RESULTS_FILE"
echo '```json' >> "$RESULTS_FILE"
curl -sf "$BASE_URL/actuator/metrics/navigation.cache.total" 2>/dev/null | python3 -m json.tool >> "$RESULTS_FILE" 2>/dev/null || echo "메트릭 미수집 (요청 없음)" >> "$RESULTS_FILE"
echo '```' >> "$RESULTS_FILE"

echo ""
echo "=== 완료. 결과: $RESULTS_FILE ==="
cat "$RESULTS_FILE"
