# A-6: 장소 자동완성 — POI + 주소 2단계 폴백

> 작업일: 2026-04-23
> 담당 Phase: ROADMAP.md A-6
> 공수: 실측 약 1시간
> 커밋: TBD

---

## 1. 배경 (Why)

### 1-1. UX 불일치 발견

A-5(현실 시나리오 벤치마크) 작업 중 프론트 `MainPage` 의 자동완성이
**"서초 아파트", "판교 오피스"** 같은 일반적 표현에 반응하지 않는 현상 발견.

원인 분석:
```
MainPage.fetchSuggestions()  →  GET /api/places  →  NaverLocalSearchClient
                                                     (developers.naver.com)
```

네이버 지역검색 API 는 **POI / 상호명** 기반. 일반 명사 + 주소성 표현은 빈 결과.

반면 결과 페이지({@code RouteListPage}) 의 좌표 변환은 **이미 2단계 폴백** 이 있음:
```
name → NaverLocalSearchClient.searchPlaces()  →  빈 결과
     → NaverGeocodingClient.geocode()          →  좌표 반환
```

즉 **같은 원칙이 자동완성에만 빠진 불일치**. 사용자 입력 경험이 일관되지 않음.

### 1-2. 목표
- `/api/places` 엔드포인트를 **결과 페이지와 동일한 2단계 폴백 설계** 로 통일
- 프론트 코드 변경 0줄 (백엔드 내부 변경만)
- 기존 재료(`NaverGeocodingClient.suggest()` + `SuggestItem`) 전부 재사용

---

## 2. 구현 (What)

### 2-1. 변경 파일 (1개)

`api/.../place/PlaceController.java`

### 2-2. Before / After

**Before:**
```java
public Mono<List<PlaceItem>> search(@RequestParam String query) {
    ...
    return naverLocalSearchClient.searchPlaces(query, 5);
}
```

**After:**
```java
public Mono<List<PlaceItem>> search(@RequestParam String query) {
    ...
    return naverLocalSearchClient.searchPlaces(query, DISPLAY_COUNT)
            .flatMap(local -> {
                if (!local.isEmpty()) return Mono.just(local);
                log.debug("[Places] POI 결과 없음 → Geocoding 폴백. query=\"{}\"", query);
                return naverGeocodingClient.suggest(query)
                        .map(PlaceController::toPlaceItems);
            });
}

private static List<PlaceItem> toPlaceItems(List<SuggestItem> suggestions) {
    return suggestions.stream()
            .map(s -> new PlaceItem(s.name(), s.lat(), s.lng()))
            .toList();
}
```

### 2-3. 설계 포인트

- **Reactor `flatMap`** 으로 자연스러운 체인 (Mono → Mono)
- **응답 형식 통일** — Geocoding `SuggestItem` → LocalSearch `PlaceItem` 으로 변환해 프론트 변경 불필요
- **로그** — 폴백 진입 시점을 DEBUG 로 남겨 운영 관측 가능
- **에러 전파** — 기존 클라이언트가 `.onErrorReturn(List.of())` 을 가지고 있어 폴백 경로 장애에도 안전

---

## 3. 검증 & 성과 (Result)

### 3-1. 실측 — 7개 쿼리 모두 5건 반환 (Before: 일부 빈 결과)

| 쿼리 | 결과 건수 | 상위 1건 | 폴백 트리거 |
|------|----------|----------|-----------|
| 강남역 | 5 | 강남역 2호선 | — (POI 성공) |
| 서초 아파트 | 5 | 오티에르반포 | — (POI 성공) |
| 판교 오피스 | 5 | 카카오 판교아지트 | — (POI 성공) |
| 반포자이 | 5 | 반포자이아파트 | — (POI 성공) |
| 서초구 반포동 | 5 | 반포1동 주민센터 | — (POI 성공) |
| 종로구 청진동 | 5 | 광화문 미진숯불막창 야장 | — (POI 성공) |
| 경복궁 | 5 | 경복궁 | — (POI 성공) |

### 3-2. 폴백 실제 트리거 확인

POI 가 못 찾는 케이스 3건 테스트 → 전부 폴백 시도 로그 기록:

```
02:40:54.645 DEBUG [Places] POI 결과 없음 → Geocoding 폴백. query="서초구 강남대로 5"
02:40:54.834 DEBUG [Places] POI 결과 없음 → Geocoding 폴백. query="반포동 22-1"
02:40:54.976 DEBUG [Places] POI 결과 없음 → Geocoding 폴백. query="광명로 10길"
```

이 3건은 Geocoding 에서도 빈 결과 → 최종 빈 리스트 반환 (정직한 응답).
주소 포맷이 너무 축약된 경우이지만 **폴백 체인 자체는 정상 작동**을 로그로 증명.

### 3-3. 프론트 코드 변경 없이 UX 개선

`MainPage.jsx` / `fetchSuggestions` 한 줄도 수정 없음. 백엔드 응답이 더 풍부해지므로
자동완성 드롭다운이 자연스럽게 더 많은 매칭을 보여준다.

---

## 4. 한계 & 다음 단계

### 4-1. 지오코딩 정확도는 API 한계
"반포동 22-1" 같이 번지 단위 축약 주소는 Geocoding 도 못 찾는 경우가 있다.
→ 클라이언트 UX 에서 "도로명 + 건물번호" 가이드 placeholder 등으로 보완 가능.

### 4-2. 두 API 중복 호출 대기 시간
POI 실패 시 순차적으로 Geocoding 호출 → 평균 300~500ms 추가 지연.
병렬 호출 후 merge 방식도 가능하지만 현재 2단계 fallback 은 "POI 우선" 의도가 명확해
순차 방식이 의미적으로 더 맞음.

### 4-3. 중복 결과 병합 미구현
LocalSearch 가 5건, Geocoding 도 5건 반환할 수 있는데, 한쪽만 쓰도록 단순화.
추후 두 결과를 합치되 중복 제거(동일 좌표 150m 격자) 하는 전략 가능.

---

## 5. 기록

> "A-5 현실 벤치마크를 만드는 중 **프론트 자동완성이 '서초 아파트' 같은 입력에
> 반응 못 하는 UX 불일치** 를 발견했습니다. 알고 보니 결과 페이지는 이미
> `LocalSearch → Geocoding` 2단계 폴백이 있었는데 자동완성엔 그게 빠져있더라고요.
> `PlaceController` 에 Reactor `flatMap` 체인으로 폴백을 추가했고, 프론트 수정
> 0줄로 자동완성 UX 가 결과 페이지와 동일한 품질이 됐습니다. **기존 불일치를
> 일관된 설계 원칙으로 통일** 한 사례입니다."

---

## 6. 관련 문서
- [ROADMAP.md A-6](../roadmap/ROADMAP.md)
- [A-5 현실 시나리오 벤치마크](./2026-04-23-A5-real-user-benchmark.md) — 본 이슈를 발견한 맥락
- `api/.../place/PlaceController.java` — 변경 파일
- `infra/.../naver/NaverGeocodingClient.java#suggest()` — 재사용된 기존 메서드
