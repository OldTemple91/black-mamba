#!/bin/bash
# k6 실행 헬퍼 — 로컬 설치 or Docker 자동 선택
#
# 사용법:
#   ./scripts/k6/run.sh smoke
#   ./scripts/k6/run.sh load
#   ./scripts/k6/run.sh stress
#   ./scripts/k6/run.sh spike
#   ./scripts/k6/run.sh cache
#
# 환경변수:
#   BASE_URL: 테스트 대상 서버 (기본: http://localhost:8081)

set -e

SCRIPT="${1:-smoke}"
BASE_URL="${BASE_URL:-http://localhost:8081}"
SCRIPT_PATH="scripts/k6/${SCRIPT}.js"

if [ ! -f "$SCRIPT_PATH" ]; then
  echo "❌ 스크립트 없음: $SCRIPT_PATH"
  echo "사용 가능: smoke | load | stress | spike | cache"
  exit 1
fi

mkdir -p output

echo "=== k6 부하 테스트: $SCRIPT ==="
echo "대상: $BASE_URL"
echo ""

# 서버 헬스체크
if ! curl -sf "$BASE_URL/actuator/health" > /dev/null 2>&1; then
  echo "⚠️  서버 응답 없음: $BASE_URL"
  echo "   먼저 서버를 실행하세요: ./gradlew :api:bootRun"
  exit 1
fi

# k6 로컬 설치 우선, 없으면 Docker
if command -v k6 > /dev/null 2>&1; then
  echo "▶ 로컬 k6 사용"
  BASE_URL="$BASE_URL" k6 run "$SCRIPT_PATH"
else
  echo "▶ Docker k6 사용 (grafana/k6 이미지)"
  docker run --rm -i \
    --network host \
    -v "$(pwd):/work" \
    -w /work \
    -e BASE_URL="$BASE_URL" \
    grafana/k6:latest run "$SCRIPT_PATH"
fi
