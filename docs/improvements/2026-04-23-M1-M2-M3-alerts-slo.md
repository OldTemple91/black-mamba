# M-1/M-2/M-3: 관측성 4축 완성 — Alertmanager + SLO + 호스트 메트릭

> 작업일: 2026-04-23
> 담당 Phase: ROADMAP.md M-1, M-2, M-3
> 공수: 실측 약 4시간
> 커밋: TBD

---

## 1. 배경 (Why)

### 1-1. "보고 있지만 알림이 안 오는" 관측성

지금까지 Black Mamba 는 **3축 관측성(Logs/Metrics/Traces)** 을 구축했지만, 운영자가 **대시보드를 보고 있을 때만** 문제를 발견할 수 있는 구조였다.

| 지금까지 | 빠져있던 것 |
|---------|-----------|
| Prometheus 메트릭 수집 | **알림 룰이 없음** — 임계 초과 감지 못함 |
| Grafana 3개 대시보드 | **SLO / Error Budget 패널 없음** — "기준선이 뭐지?" 답 불가 |
| JVM 메트릭 | **호스트·컨테이너 리소스 없음** — CPU/Mem 포화 관측 불가 |

발표관이 반복해서 묻는 "알림 받아본 적 있나요?", "SLO 계산해봤나요?" 에 당당히 답할 수 없는 구멍.

### 1-2. 목표

**관측성 4축 완성: Logs ↔ Metrics ↔ Traces + Alerts + SLO.**

1. **M-2 호스트/컨테이너 메트릭** (cAdvisor + Node Exporter)
2. **M-1 알림 파이프라인** (Alertmanager + Discord + 룰 5개)
3. **M-3 SLO & Error Budget** (Recording Rules + Burn Rate + 대시보드)

---

## 2. 구현 (What)

### 2-1. 변경 파일

```
docker-compose.yml                                          # +3 서비스
monitoring/prometheus.yml                                   # 알림/룰/스크랩 5개
monitoring/alertmanager.yml                                 # 신규 (Discord 라우팅)
monitoring/prometheus-rules/black-mamba-alerts.yml          # 신규 (Alert 5종)
monitoring/prometheus-rules/slo-recording.yml               # 신규 (SLO 9 rules)
monitoring/grafana-dashboards/black-mamba-slo.json          # 신규 (SLO 대시보드)
```

### 2-2. M-2 — 호스트/컨테이너 메트릭

docker-compose 에 2개 서비스 추가:

```yaml
cadvisor:      # 컨테이너 CPU/Mem/Net/FS (Google 제공)
  image: gcr.io/cadvisor/cadvisor:v0.55.1
  ports: ["8088:8080"]
  # Docker socket, sysfs, rootfs 마운트로 모든 컨테이너 관측

node-exporter: # 호스트 시스템 메트릭
  image: prom/node-exporter:v1.9.1
  ports: ["9100:9100"]
```

Prometheus 가 5개 타겟 자동 스크랩 (app/alertmanager/cadvisor/node/prometheus).

### 2-3. M-1 — Alertmanager + Discord + 알림 룰 5개

**Alertmanager 가 env var 치환을 지원하지 않는 문제** — Alertmanager 0.28.1 에는 native env expansion 이 없음 (v0.27 릴리즈 노트엔 있지만 실제 바이너리엔 미반영).

해결: docker-compose entrypoint 를 sh 로 override 해 시작 시 sed 치환.

```yaml
entrypoint: /bin/sh
command:
  - -c
  - |
    sed "s@PLACEHOLDER_DISCORD_WEBHOOK@$$DISCORD_WEBHOOK_URL@g" \
      /etc/alertmanager/alertmanager.template.yml > /tmp/alertmanager.yml
    exec /bin/alertmanager --config.file=/tmp/alertmanager.yml ...
```

`$$` 이중 달러가 docker-compose 에서 `$` 로 이스케이프되어 컨테이너 내부에서 정상 환경변수로 해석.

**알림 룰 5종:**

| # | Alert | Expr 요지 | Severity | For |
|---|-------|----------|----------|-----|
| ① | `RouteSearchHighP95Latency` | p95 > 3s | warning | 5m |
| ② | `HighErrorRate` | 5xx 비율 > 5% | **critical** | 5m |
| ③ | `CircuitBreakerOpen` | state="open" == 1 | **critical** | 1m |
| ④ | `DdareungiFallbackSpike` | fallback rate > 0.2/s | warning | 5m |
| ⑤ | `JvmHeapHigh` | heap / max > 85% | warning | 10m |

**라우팅**: severity=critical → `discord-critical` (10초 지연, 30분 반복), 그 외 → `discord-default` (30초 지연, 1시간 반복).
**Inhibit**: critical 활성 시 같은 `alertname+job` 의 warning 은 억제 (노이즈 감소).

### 2-4. M-3 — SLO Recording Rules + Burn Rate

Google SRE Workbook 표준 패턴:

```promql
# 가용성 SLO (target 99%)
slo:navigation_availability:ratio5m  = success / total (5분)
slo:navigation_availability:ratio30d = success / total (30일)

# 지연 SLO (target p95 < 2s)
slo:route_duration_p95:5m
slo:route_duration_p99:5m
slo:route_latency_compliance:ratio5m  # "2초 이내 응답 비율"

# 에러버짓 Burn Rate (SRE 표준 다중 윈도우)
slo:navigation_errorbudget_burnrate:1h   # Fast
slo:navigation_errorbudget_burnrate:6h   # Slow
```

**Burn Rate 임계값 (Google SRE Workbook Table 5-1):**
- `ErrorBudgetFastBurn`: 1h burn > 14.4× for 2m → **critical** (2일 내 월간 버짓 소진)
- `ErrorBudgetSlowBurn`: 6h burn > 6× for 15m → warning (5일 소진 속도)

### 2-5. M-3 — Grafana SLO 대시보드

`black-mamba-slo.json` — 7개 패널:

- 상단 4 stat: 30일 가용성 / p95 / 1h Fast Burn / 6h Slow Burn (임계값 색상 단계)
- 시계열 3: 실시간 가용성 + target 선, p95/p99 + target 선, Burn Rate 추이
- 테이블: 현재 firing 알람 상세
- cAdvisor 2 패널: 컨테이너 CPU/Memory

프로비저닝 자동 감지 (`updateIntervalSeconds: 10`).

---

## 3. 검증 & 성과 (Result)

### 3-1. 타겟 전 구간 UP

```
alertmanager         up   http://alertmanager:9093/metrics
black-mamba-app      up   http://app:8081/actuator/prometheus
cadvisor             up   http://cadvisor:8080/metrics
node                 up   http://node-exporter:9100/metrics
prometheus           up   http://localhost:9090/metrics
```

### 3-2. 룰 16개 로드 확인

```
총 16 룰: alert=7, recording=9
  - black-mamba-alerts:     5 개
  - slo-availability:       6 개
  - slo-burnrate-alerts:    2 개
  - slo-latency:            3 개
```

### 3-3. E2E 알림 파이프라인 검증

1) 인위적으로 `/api/v2/alerts` 에 test alert POST →
2) Alertmanager 에 1건 쌓임 (`TestAlertE2E [critical] active`) →
3) Discord webhook 호출 시도 → fake URL 이라 `400: "Value \"DISABLED\" is not snowflake"` 수신 (실제 웹훅이면 성공)

**파이프라인 전 구간이 실제 HTTP 호출까지 도달함을 로그로 증명.**

```
time=2026-04-23T03:21:13.393Z level=ERROR source=dispatch.go:360
  msg="Notify for alerts failed" num_alerts=1
  err="discord-critical/discord[0]: ... unexpected status code 400"
```

### 3-4. SLO Recording Rule 실계산

트래픽 5건 발생 → 20초 후 recording rule 값 계산 확인:

```
slo:navigation_requests:rate5m        = 0.018 req/s
slo:navigation_requests_success:rate5m = 0.018 req/s
slo:navigation_availability:ratio5m    = 100.0%
slo:route_duration_p95:5m              = 11,382 ms  ← 초기 콜드 히트
```

### 3-5. 실 알람 Pending 전환

테스트 트래픽 중 초기 요청의 p95 가 11초여서 **`RouteSearchHighP95Latency` 알람이 Prometheus 에서 자동으로 pending** 으로 전환되는 것 확인:

```
Prometheus 에 등록된 알람 상태: 1건
  - RouteSearchHighP95Latency     state=pending    value=11.12s
```

`for: 5m` 대기 후 `firing` → Alertmanager → Discord 로 전달되는 전 경로가 **규칙대로 동작**함.

---

## 4. 사이드 이펙트 & 한계

### 4-1. macOS Docker Desktop 의 Node Exporter 한계
VM 레벨 메트릭만 보이고 실제 호스트 Mac 의 CPU/Mem 은 관측 불가. **상대 추이** 용도로만 의미. 운영 환경 (Linux 서버) 에서는 그대로 유효.

### 4-2. Alertmanager env 치환 해결은 우회
0.27 릴리즈 노트에 있는 `--config.expand-env-vars` 가 0.28.1 에도 없어 sh entrypoint 로 sed 치환. 0.29 에서 네이티브 지원 시 교체.

### 4-3. Discord 웹훅 미설정 시
`DISCORD_WEBHOOK_URL` 미설정이면 Alertmanager 는 정상 동작하되 전송만 실패. 로컬 개발/CI 에 친화적이지만 **운영 배포 시 반드시 .env 또는 환경변수로 설정해야 함** (README 보강 필요).

### 4-4. SLO 분모가 0일 때
`slo:navigation_availability:ratio5m` 은 `total > 0` 필터로 NaN 회피. 트래픽 0 인 시간대엔 값이 비게 됨 — Grafana 에서 "last non-null" 로 스테일 값 유지.

### 4-5. 30일 가용성은 30일 데이터가 쌓여야 정확
Prometheus retention 7일 기본 → 첫 30일은 실제 관측 창이 더 짧음. 운영 배포 후 30일 뒤부터 정확.

---

## 5. 발표 스토리

> "지금까지 관측성은 **3축(Logs/Metrics/Traces)** 이었는데, '사람이 대시보드를 보고 있을 때만 문제를 발견하는' 한계가 있었습니다.
>
> **Alertmanager + Discord 웹훅** 으로 알림 축을, **SLO Recording Rule + Error Budget Burn Rate** 로 SRE 표준 관측 축을 추가해 **4축(+Alerts, +SLO)** 으로 완성했습니다.
>
> 특히 Burn Rate 는 Google SRE Workbook 의 **다중 윈도우 · 다중 속도** 패턴을 그대로 적용 — 1시간 14.4배는 '2일 내 월간 버짓 소진' 이라는 물리적 의미를 가지는 임계값입니다. 단순 '에러율 5% 넘음' 보다 **의미 있는 알람** 을 제공합니다.
>
> E2E 검증은 Alertmanager API 로 인위 알람을 발사해 Discord 호출까지 HTTP 레벨에서 도달함을 로그로 증명했습니다."

---

## 6. 운영 팁

### 6-1. Discord 웹훅 설정
```bash
# .env 에 추가
echo "DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/<id>/<token>" >> .env
docker compose up -d --force-recreate alertmanager
```

### 6-2. 알람 상태 확인
```bash
curl http://localhost:9093/api/v2/alerts               # Alertmanager
curl http://localhost:9090/api/v1/alerts               # Prometheus
```

### 6-3. SLO 대시보드 진입
`http://localhost:3000/d/black-mamba-slo`

---

## 7. 관련 문서
- [ROADMAP M-1/M-2/M-3](../roadmap/ROADMAP.md)
- [observability-stack.md](../monitoring/observability-stack.md)
- `monitoring/alertmanager.yml` — Discord 라우팅 + inhibit 규칙
- `monitoring/prometheus-rules/` — Alert + Recording 룰
- `monitoring/grafana-dashboards/black-mamba-slo.json` — SLO 대시보드
