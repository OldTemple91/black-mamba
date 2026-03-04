# Black Mamba Navigation — 구현 계획

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 대중교통 + 개인 이동수단(따릉이/킥보드)을 혼합한 멀티모달 최적 경로 탐색 서비스 구현

**Architecture:** Spring Boot 4모듈(api/application/domain/infra) 백엔드 + React 프론트엔드.
ODsay API로 대중교통 경로를 받아 중간 환승 지점에서 이동수단으로 교체하는 최적 조합을 탐색.
외부 API 병렬 호출은 Spring MVC + WebClient 조합으로 처리.

**Tech Stack:** Java 21, Spring Boot 3.3, Gradle 멀티모듈, WebClient, JUnit 5, React 18, TailwindCSS, 네이버 지도 JS SDK

---

## Task 1: Gradle 멀티모듈 프로젝트 셋업

**Files:**
- Create: `settings.gradle`
- Create: `build.gradle` (루트)
- Create: `api/build.gradle`
- Create: `application/build.gradle`
- Create: `domain/build.gradle`
- Create: `infra/build.gradle`
- Create: `api/src/main/java/com/blackmamba/navigation/ApiApplication.java`

**Step 1: settings.gradle 작성**

```groovy
rootProject.name = 'black-mamba'

include 'api'
include 'application'
include 'domain'
include 'infra'
```

**Step 2: 루트 build.gradle 작성**

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.3.0' apply false
    id 'io.spring.dependency-management' version '1.1.4' apply false
}

subprojects {
    apply plugin: 'java'
    apply plugin: 'io.spring.dependency-management'

    group = 'com.blackmamba'
    version = '0.0.1-SNAPSHOT'

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    repositories {
        mavenCentral()
    }

    dependencyManagement {
        imports {
            mavenBom "org.springframework.boot:spring-boot-dependencies:3.3.0"
        }
    }

    dependencies {
        testImplementation 'org.springframework.boot:spring-boot-starter-test'
    }
}
```

**Step 3: domain/build.gradle 작성**

```groovy
// 외부 의존성 없음 - 순수 도메인 모델
dependencies {}
```

**Step 4: application/build.gradle 작성**

```groovy
dependencies {
    implementation project(':domain')
    implementation 'org.springframework.boot:spring-boot-starter'
}
```

**Step 5: infra/build.gradle 작성**

```groovy
dependencies {
    implementation project(':domain')
    implementation 'org.springframework.boot:spring-boot-starter-webflux' // WebClient용
    implementation 'com.fasterxml.jackson.core:jackson-databind'
}
```

**Step 6: api/build.gradle 작성**

```groovy
apply plugin: 'org.springframework.boot'

dependencies {
    implementation project(':application')
    implementation project(':domain')
    implementation project(':infra')
    implementation 'org.springframework.boot:spring-boot-starter-web'
}
```

**Step 7: ApiApplication.java 작성**

```java
package com.blackmamba.navigation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.blackmamba")
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
```

**Step 8: 빌드 확인**

```bash
cd /Users/sjw/Desktop/black-mamba
./gradlew assemble
```
Expected: BUILD SUCCESSFUL

**Step 9: Commit**

```bash
git add .
git commit -m "feat: Gradle 멀티모듈 프로젝트 초기 셋업 (api/application/domain/infra)"
```

---

## Task 2: 도메인 모델 정의

**Files:**
- Create: `domain/src/main/java/com/blackmamba/navigation/domain/location/Location.java`
- Create: `domain/src/main/java/com/blackmamba/navigation/domain/route/RouteType.java`
- Create: `domain/src/main/java/com/blackmamba/navigation/domain/route/LegType.java`
- Create: `domain/src/main/java/com/blackmamba/navigation/domain/route/MobilityType.java`
- Create: `domain/src/main/java/com/blackmamba/navigation/domain/route/MobilityInfo.java`
- Create: `domain/src/main/java/com/blackmamba/navigation/domain/route/TransitInfo.java`
- Create: `domain/src/main/java/com/blackmamba/navigation/domain/route/Leg.java`
- Create: `domain/src/main/java/com/blackmamba/navigation/domain/route/Comparison.java`
- Create: `domain/src/main/java/com/blackmamba/navigation/domain/route/Route.java`
- Test: `domain/src/test/java/com/blackmamba/navigation/domain/route/RouteTest.java`

**Step 1: 테스트 먼저 작성**

```java
// RouteTest.java
package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RouteTest {

    @Test
    void 총_소요시간은_모든_Leg의_합이다() {
        Location seoul = new Location("서울역", 37.5547, 126.9706);
        Location banpo = new Location("반포역", 37.5040, 127.0050);
        Location dest = new Location("목적지", 37.4979, 127.0276);

        Leg transitLeg = new Leg(LegType.TRANSIT, "BUS", 18, 4200, seoul, banpo, null, null);
        Leg kickboardLeg = new Leg(LegType.KICKBOARD, "KICKBOARD_SHARED", 9, 1800, banpo, dest, null, null);

        Route route = Route.of(List.of(transitLeg, kickboardLeg), RouteType.TRANSIT_WITH_KICKBOARD);

        assertThat(route.totalMinutes()).isEqualTo(27);
    }

    @Test
    void 절약_시간을_계산할_수_있다() {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.4, 127.1);
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 27, 5000, a, b, null, null);

        Route route = Route.of(List.of(leg), RouteType.TRANSIT_ONLY);
        Route routeWithSaving = route.withComparison(new Comparison(45, 18));

        assertThat(routeWithSaving.comparison().savedMinutes()).isEqualTo(18);
    }
}
```

**Step 2: 테스트 실패 확인**

```bash
./gradlew :domain:test
```
Expected: FAIL (클래스 없음)

**Step 3: Location 구현**

```java
// Location.java
package com.blackmamba.navigation.domain.location;

public record Location(String name, double lat, double lng) {}
```

**Step 4: Enum 구현**

```java
// RouteType.java
package com.blackmamba.navigation.domain.route;

public enum RouteType {
    TRANSIT_ONLY,
    TRANSIT_WITH_BIKE,
    TRANSIT_WITH_KICKBOARD,
    BIKE_FIRST_TRANSIT
}
```

```java
// LegType.java
package com.blackmamba.navigation.domain.route;

public enum LegType { TRANSIT, WALK, BIKE, KICKBOARD }
```

```java
// MobilityType.java
package com.blackmamba.navigation.domain.route;

public enum MobilityType { PERSONAL, DDAREUNGI, KICKBOARD_SHARED }
```

**Step 5: MobilityInfo, TransitInfo, Comparison 구현**

```java
// MobilityInfo.java
package com.blackmamba.navigation.domain.route;

public record MobilityInfo(
        MobilityType mobilityType,
        String operatorName,
        String deviceId,
        int batteryLevel,
        String stationName,
        double lat,
        double lng,
        int availableCount,
        int distanceMeters
) {}
```

```java
// TransitInfo.java
package com.blackmamba.navigation.domain.route;

public record TransitInfo(String lineName, String lineColor, int stationCount) {}
```

```java
// Comparison.java
package com.blackmamba.navigation.domain.route;

public record Comparison(int originalMinutes, int savedMinutes) {}
```

**Step 6: Leg 구현**

```java
// Leg.java
package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;

public record Leg(
        LegType type,
        String mode,
        int durationMinutes,
        int distanceMeters,
        Location start,
        Location end,
        TransitInfo transitInfo,
        MobilityInfo mobilityInfo
) {}
```

**Step 7: Route 구현**

```java
// Route.java
package com.blackmamba.navigation.domain.route;

import java.util.List;

public record Route(
        String routeId,
        RouteType type,
        int totalMinutes,
        int totalCostWon,
        double score,
        boolean recommended,
        List<Leg> legs,
        Comparison comparison
) {
    public static Route of(List<Leg> legs, RouteType type) {
        int total = legs.stream().mapToInt(Leg::durationMinutes).sum();
        return new Route(
                java.util.UUID.randomUUID().toString(),
                type, total, 0, 0.0, false, legs, null
        );
    }

    public Route withComparison(Comparison comparison) {
        return new Route(routeId, type, totalMinutes, totalCostWon, score, recommended, legs, comparison);
    }

    public Route withScore(double score, boolean recommended) {
        return new Route(routeId, type, totalMinutes, totalCostWon, score, recommended, legs, comparison);
    }
}
```

**Step 8: 테스트 통과 확인**

```bash
./gradlew :domain:test
```
Expected: PASS

**Step 9: Commit**

```bash
git add .
git commit -m "feat: 도메인 모델 정의 (Route, Leg, Location, MobilityInfo 등)"
```

---

## Task 3: ODsay API Client 구현

**Files:**
- Create: `infra/src/main/java/com/blackmamba/navigation/infra/odsay/OdsayRouteClient.java`
- Create: `infra/src/main/java/com/blackmamba/navigation/infra/odsay/dto/OdsayRouteResponse.java`
- Create: `infra/src/main/java/com/blackmamba/navigation/infra/odsay/OdsayRouteMapper.java`
- Create: `infra/src/main/resources/application.yml`
- Test: `infra/src/test/java/com/blackmamba/navigation/infra/odsay/OdsayRouteClientTest.java`

> ODsay API 키 발급: https://lab.odsay.com/ 회원가입 후 발급

**Step 1: 테스트 먼저 작성 (Mock 기반)**

```java
// OdsayRouteClientTest.java
package com.blackmamba.navigation.infra.odsay;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OdsayRouteClientTest {

    @Test
    void ODsay_응답을_Leg_목록으로_변환한다() {
        OdsayRouteMapper mapper = new OdsayRouteMapper();

        // 샘플 ODsay 응답 구조 (실제 API 응답 형식)
        OdsayRouteResponse.Path path = buildSamplePath();
        List<Leg> legs = mapper.toLegs(path);

        assertThat(legs).isNotEmpty();
        assertThat(legs.get(0).type()).isEqualTo(LegType.TRANSIT);
    }

    private OdsayRouteResponse.Path buildSamplePath() {
        // 테스트용 샘플 데이터 구성
        return new OdsayRouteResponse.Path(
                new OdsayRouteResponse.PathInfo(30, 2, 1500),
                List.of(
                        new OdsayRouteResponse.SubPath(1, 20, 4200,
                                new OdsayRouteResponse.Lane("간선 140", "#0052A4"),
                                List.of(
                                        new OdsayRouteResponse.Station("서울역", 37.5547, 126.9706),
                                        new OdsayRouteResponse.Station("반포역", 37.5040, 127.0050)
                                )),
                        new OdsayRouteResponse.SubPath(3, 10, 700, null, List.of())
                )
        );
    }
}
```

**Step 2: OdsayRouteResponse DTO 구현**

```java
// OdsayRouteResponse.java
package com.blackmamba.navigation.infra.odsay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayRouteResponse(Result result) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(Path path) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Path(PathInfo info, List<SubPath> subPath) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PathInfo(int totalTime, int transitCount, int payment) {}

    // trafficType: 1=지하철, 2=버스, 3=도보
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubPath(
            int trafficType,
            int sectionTime,
            int distance,
            Lane lane,
            List<Station> passStopList
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lane(String name, String busColor) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Station(
            String stationName,
            @JsonProperty("x") double lng,
            @JsonProperty("y") double lat
    ) {}
}
```

**Step 3: OdsayRouteMapper 구현**

```java
// OdsayRouteMapper.java
package com.blackmamba.navigation.infra.odsay;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import com.blackmamba.navigation.infra.odsay.dto.OdsayRouteResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OdsayRouteMapper {

    public List<Leg> toLegs(OdsayRouteResponse.Path path) {
        return path.subPath().stream()
                .map(this::toLeg)
                .toList();
    }

    private Leg toLeg(OdsayRouteResponse.SubPath subPath) {
        LegType legType = switch (subPath.trafficType()) {
            case 1 -> LegType.TRANSIT; // 지하철
            case 2 -> LegType.TRANSIT; // 버스
            case 3 -> LegType.WALK;
            default -> LegType.WALK;
        };

        String mode = switch (subPath.trafficType()) {
            case 1 -> "SUBWAY";
            case 2 -> "BUS";
            default -> "WALK";
        };

        Location start = extractStart(subPath);
        Location end = extractEnd(subPath);

        TransitInfo transitInfo = null;
        if (legType == LegType.TRANSIT && subPath.lane() != null) {
            transitInfo = new TransitInfo(
                    subPath.lane().name(),
                    subPath.lane().busColor(),
                    subPath.passStopList().size()
            );
        }

        return new Leg(legType, mode, subPath.sectionTime(),
                subPath.distance(), start, end, transitInfo, null);
    }

    private Location extractStart(OdsayRouteResponse.SubPath subPath) {
        if (subPath.passStopList() != null && !subPath.passStopList().isEmpty()) {
            OdsayRouteResponse.Station s = subPath.passStopList().get(0);
            return new Location(s.stationName(), s.lat(), s.lng());
        }
        return new Location("출발", 0, 0);
    }

    private Location extractEnd(OdsayRouteResponse.SubPath subPath) {
        List<OdsayRouteResponse.Station> stations = subPath.passStopList();
        if (stations != null && !stations.isEmpty()) {
            OdsayRouteResponse.Station s = stations.get(stations.size() - 1);
            return new Location(s.stationName(), s.lat(), s.lng());
        }
        return new Location("도착", 0, 0);
    }
}
```

**Step 4: OdsayRouteClient 구현**

```java
// OdsayRouteClient.java
package com.blackmamba.navigation.infra.odsay;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.infra.odsay.dto.OdsayRouteResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class OdsayRouteClient {

    private static final String BASE_URL = "https://api.odsay.com/v1/api";

    private final WebClient webClient;
    private final OdsayRouteMapper mapper;
    private final String apiKey;

    public OdsayRouteClient(
            OdsayRouteMapper mapper,
            @Value("${odsay.api-key}") String apiKey
    ) {
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.webClient = WebClient.builder().baseUrl(BASE_URL).build();
    }

    public Mono<List<Leg>> getTransitRoute(Location origin, Location destination) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/searchPubTransPathT")
                        .queryParam("apiKey", apiKey)
                        .queryParam("SX", origin.lng())
                        .queryParam("SY", origin.lat())
                        .queryParam("EX", destination.lng())
                        .queryParam("EY", destination.lat())
                        .build())
                .retrieve()
                .bodyToMono(OdsayRouteResponse.class)
                .map(response -> mapper.toLegs(response.result().path()));
    }

    public Mono<Integer> getTransitTimeMinutes(Location origin, Location destination) {
        return getTransitRoute(origin, destination)
                .map(legs -> legs.stream().mapToInt(Leg::durationMinutes).sum());
    }
}
```

**Step 5: application.yml 설정**

```yaml
# infra/src/main/resources/application.yml
odsay:
  api-key: ${ODSAY_API_KEY:your-api-key-here}
```

**Step 6: 테스트 통과 확인**

```bash
./gradlew :infra:test
```
Expected: PASS

**Step 7: Commit**

```bash
git add .
git commit -m "feat: ODsay API Client 구현 (대중교통 경로 조회)"
```

---

## Task 4: 따릉이 API Client 구현

**Files:**
- Create: `infra/src/main/java/com/blackmamba/navigation/infra/ddareungi/DdareungiApiClient.java`
- Create: `infra/src/main/java/com/blackmamba/navigation/infra/ddareungi/dto/DdareungiStationResponse.java`
- Test: `infra/src/test/java/com/blackmamba/navigation/infra/ddareungi/DdareungiApiClientTest.java`

> 따릉이 API 키 발급: https://data.seoul.go.kr → "공공자전거 실시간 대여정보" 검색 후 활용신청

**Step 1: 테스트 먼저 작성**

```java
// DdareungiApiClientTest.java
package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DdareungiApiClientTest {

    @Test
    void 반경_내_따릉이_대여소를_필터링한다() {
        DdareungiApiClient client = new DdareungiApiClient(null, "test-key");

        List<DdareungiStation> stations = List.of(
                new DdareungiStation("반포역 1번출구", 37.5038, 127.0048, 5),
                new DdareungiStation("반포한강공원", 37.5180, 127.0000, 3),  // 멀리 있음
                new DdareungiStation("반포역 2번출구", 37.5040, 127.0052, 0)
        );

        // 37.5040, 127.0050 기준 반경 300m 내, 잔여대수 > 0
        List<DdareungiStation> nearby = client.filterNearby(stations, 37.5040, 127.0050, 300);

        assertThat(nearby).hasSize(1);
        assertThat(nearby.get(0).stationName()).isEqualTo("반포역 1번출구");
    }
}
```

**Step 2: DdareungiStation DTO 구현**

```java
// DdareungiStation.java
package com.blackmamba.navigation.infra.ddareungi.dto;

public record DdareungiStation(
        String stationName,
        double lat,
        double lng,
        int availableCount
) {}
```

**Step 3: DdareungiApiClient 구현**

```java
// DdareungiApiClient.java
package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class DdareungiApiClient {

    private static final String BASE_URL = "http://openapi.seoul.go.kr:8088";
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final WebClient webClient;
    private final String apiKey;

    public DdareungiApiClient(WebClient.Builder builder,
                               @Value("${ddareungi.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.webClient = builder != null
                ? builder.baseUrl(BASE_URL).build()
                : WebClient.builder().baseUrl(BASE_URL).build();
    }

    public Mono<List<DdareungiStation>> getNearbyStations(double lat, double lng, int radiusMeters) {
        return webClient.get()
                .uri("/{apiKey}/json/bikeList/1/1000/", apiKey)
                .retrieve()
                .bodyToMono(DdareungiStationResponse.class)
                .map(response -> filterNearby(response.toStations(), lat, lng, radiusMeters));
    }

    List<DdareungiStation> filterNearby(List<DdareungiStation> stations,
                                         double lat, double lng, int radiusMeters) {
        return stations.stream()
                .filter(s -> s.availableCount() > 0)
                .filter(s -> distanceMeters(lat, lng, s.lat(), s.lng()) <= radiusMeters)
                .toList();
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
```

**Step 4: 테스트 통과 확인**

```bash
./gradlew :infra:test
```
Expected: PASS

**Step 5: Commit**

```bash
git add .
git commit -m "feat: 따릉이 API Client 구현 (실시간 대여소 조회)"
```

---

## Task 5: 국토부 TAGO 킥보드 API Client 구현

**Files:**
- Create: `infra/src/main/java/com/blackmamba/navigation/infra/kickboard/KickboardApiClient.java`
- Create: `infra/src/main/java/com/blackmamba/navigation/infra/kickboard/dto/KickboardDevice.java`
- Test: `infra/src/test/java/com/blackmamba/navigation/infra/kickboard/KickboardApiClientTest.java`

> TAGO API 키 발급: https://www.data.go.kr → "공유 퍼스널모빌리티정보" 검색 → 활용신청

**Step 1: 테스트 먼저 작성**

```java
// KickboardApiClientTest.java
package com.blackmamba.navigation.infra.kickboard;

import com.blackmamba.navigation.infra.kickboard.dto.KickboardDevice;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class KickboardApiClientTest {

    @Test
    void 반경내_킥보드를_배터리_조건과_함께_필터링한다() {
        KickboardApiClient client = new KickboardApiClient(null, "test-key");

        List<KickboardDevice> devices = List.of(
                new KickboardDevice("DEV_001", "씽씽", 37.5040, 127.0052, 85),
                new KickboardDevice("DEV_002", "킥고잉", 37.5900, 127.1000, 90), // 멀리있음
                new KickboardDevice("DEV_003", "Lime", 37.5041, 127.0051, 10)   // 배터리 부족
        );

        List<KickboardDevice> nearby = client.filterNearby(devices, 37.5040, 127.0050, 300);

        assertThat(nearby).hasSize(1);
        assertThat(nearby.get(0).deviceId()).isEqualTo("DEV_001");
    }
}
```

**Step 2: KickboardDevice DTO 구현**

```java
// KickboardDevice.java
package com.blackmamba.navigation.infra.kickboard.dto;

public record KickboardDevice(
        String deviceId,
        String operatorName,
        double lat,
        double lng,
        int batteryLevel
) {}
```

**Step 3: KickboardApiClient 구현**

```java
// KickboardApiClient.java
package com.blackmamba.navigation.infra.kickboard;

import com.blackmamba.navigation.infra.kickboard.dto.KickboardDevice;
import com.blackmamba.navigation.infra.kickboard.dto.KickboardResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class KickboardApiClient {

    private static final String BASE_URL = "http://apis.data.go.kr/1613000/PersonalMobilityInfoService";
    private static final int MIN_BATTERY = 20; // 배터리 20% 미만 제외
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final WebClient webClient;
    private final String apiKey;

    public KickboardApiClient(WebClient.Builder builder,
                               @Value("${tago.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.webClient = builder != null
                ? builder.baseUrl(BASE_URL).build()
                : WebClient.builder().baseUrl(BASE_URL).build();
    }

    public Mono<List<KickboardDevice>> getNearbyDevices(double lat, double lng, int radiusMeters) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/getPMProvider")
                        .queryParam("serviceKey", apiKey)
                        .queryParam("region", "서울")
                        .queryParam("numOfRows", 1000)
                        .build())
                .retrieve()
                .bodyToMono(KickboardResponse.class)
                .map(response -> filterNearby(response.toDevices(), lat, lng, radiusMeters));
    }

    List<KickboardDevice> filterNearby(List<KickboardDevice> devices,
                                        double lat, double lng, int radiusMeters) {
        return devices.stream()
                .filter(d -> d.batteryLevel() >= MIN_BATTERY)
                .filter(d -> distanceMeters(lat, lng, d.lat(), d.lng()) <= radiusMeters)
                .toList();
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
```

**Step 4: 테스트 통과 확인**

```bash
./gradlew :infra:test
```
Expected: PASS

**Step 5: Commit**

```bash
git add .
git commit -m "feat: 국토부 TAGO 킥보드 API Client 구현"
```

---

## Task 6: 후보 환승 지점 선택기 (CandidatePointSelector) 구현

**Files:**
- Create: `application/src/main/java/com/blackmamba/navigation/application/route/CandidatePointSelector.java`
- Test: `application/src/test/java/com/blackmamba/navigation/application/route/CandidatePointSelectorTest.java`

**Step 1: 테스트 먼저 작성**

```java
// CandidatePointSelectorTest.java
package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CandidatePointSelectorTest {

    private final CandidatePointSelector selector = new CandidatePointSelector();

    @Test
    void 전체_경로의_30_80퍼센트_구간_정류장만_후보로_선택한다() {
        List<Leg> legs = createLegsWithStops(10); // 10개 정류장

        List<Location> candidates = selector.select(legs, mobilityConfig());

        // 30~80% → 3번째~8번째 정류장
        assertThat(candidates.size()).isBetween(3, 6);
    }

    @Test
    void 목적지까지_거리가_범위_초과인_정류장은_제외한다() {
        Location farStop = new Location("먼정류장", 37.6000, 127.0000); // 목적지와 멀리 있음
        Location nearStop = new Location("가까운정류장", 37.5050, 127.0100);
        Location dest = new Location("목적지", 37.5040, 127.0050);

        List<Location> candidates = selector.filterByMobilityRange(
                List.of(farStop, nearStop), dest, MobilityType.KICKBOARD_SHARED);

        assertThat(candidates).containsOnly(nearStop);
    }

    private List<Leg> createLegsWithStops(int count) {
        // 테스트용 버스 구간 하나 (10개 정류장)
        List<Location> stops = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new Location("정류장" + i, 37.5 + i * 0.001, 127.0))
                .toList();
        TransitInfo transitInfo = new TransitInfo("140", "#0052A4", count);
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 20, 5000,
                stops.get(0), stops.get(stops.size() - 1), transitInfo, null);
        return List.of(leg);
    }

    private MobilityConfig mobilityConfig() {
        return new MobilityConfig(MobilityType.KICKBOARD_SHARED, 5000);
    }
}
```

**Step 2: MobilityConfig 구현**

```java
// MobilityConfig.java
package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.MobilityType;

public record MobilityConfig(
        MobilityType mobilityType,
        int maxRangeMeters  // 킥보드: 5000, 자전거: 10000
) {
    public static MobilityConfig kickboard() {
        return new MobilityConfig(MobilityType.KICKBOARD_SHARED, 5000);
    }

    public static MobilityConfig bike() {
        return new MobilityConfig(MobilityType.DDAREUNGI, 10000);
    }
}
```

**Step 3: CandidatePointSelector 구현**

```java
// CandidatePointSelector.java
package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.MobilityType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CandidatePointSelector {

    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final double MIN_RATIO = 0.3;
    private static final double MAX_RATIO = 0.8;

    public List<Location> select(List<Leg> legs, MobilityConfig config) {
        List<Location> allStops = extractTransitStops(legs);
        if (allStops.size() < 3) return List.of();

        int from = (int) (allStops.size() * MIN_RATIO);
        int to = (int) (allStops.size() * MAX_RATIO);

        return allStops.subList(from, to);
    }

    public List<Location> filterByMobilityRange(List<Location> candidates,
                                                  Location destination,
                                                  MobilityType mobilityType) {
        int maxRange = mobilityType == MobilityType.KICKBOARD_SHARED ? 5000 : 10000;

        return candidates.stream()
                .filter(stop -> distanceMeters(
                        stop.lat(), stop.lng(),
                        destination.lat(), destination.lng()) <= maxRange)
                .toList();
    }

    private List<Location> extractTransitStops(List<Leg> legs) {
        List<Location> stops = new ArrayList<>();
        for (Leg leg : legs) {
            if (leg.type() == LegType.TRANSIT) {
                stops.add(leg.start());
                stops.add(leg.end());
            }
        }
        return stops;
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
```

**Step 4: 테스트 통과 확인**

```bash
./gradlew :application:test
```
Expected: PASS

**Step 5: Commit**

```bash
git add .
git commit -m "feat: CandidatePointSelector 구현 (후보 환승 지점 필터링)"
```

---

## Task 7: RouteScoreCalculator 구현

**Files:**
- Create: `application/src/main/java/com/blackmamba/navigation/application/route/RouteScoreCalculator.java`
- Test: `application/src/test/java/com/blackmamba/navigation/application/route/RouteScoreCalculatorTest.java`

**Step 1: 테스트 먼저 작성**

```java
// RouteScoreCalculatorTest.java
package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.*;
import com.blackmamba.navigation.domain.location.Location;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RouteScoreCalculatorTest {

    private final RouteScoreCalculator calculator = new RouteScoreCalculator();

    @Test
    void 시간이_짧을수록_점수가_높다() {
        Route faster = routeWithMinutes(20, 0, 1250);
        Route slower = routeWithMinutes(45, 0, 1250);

        double fastScore = calculator.calculate(faster);
        double slowScore = calculator.calculate(slower);

        assertThat(fastScore).isGreaterThan(slowScore);
    }

    @Test
    void 환승이_적을수록_점수가_높다() {
        Route lessTransfer = routeWithMinutes(30, 1, 1250);
        Route moreTransfer = routeWithMinutes(30, 3, 1250);

        double lessScore = calculator.calculate(lessTransfer);
        double moreScore = calculator.calculate(moreTransfer);

        assertThat(lessScore).isGreaterThan(moreScore);
    }

    private Route routeWithMinutes(int minutes, int transferCount, int cost) {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.4, 127.1);
        List<Leg> legs = new ArrayList<>();
        for (int i = 0; i <= transferCount; i++) {
            legs.add(new Leg(LegType.TRANSIT, "BUS", minutes / (transferCount + 1),
                    1000, a, b, null, null));
        }
        return new Route("id", RouteType.TRANSIT_ONLY, minutes, cost, 0, false, legs, null);
    }
}
```

**Step 2: RouteScoreCalculator 구현**

```java
// RouteScoreCalculator.java
package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import org.springframework.stereotype.Component;

@Component
public class RouteScoreCalculator {

    private static final double TIME_WEIGHT = 0.5;
    private static final double TRANSFER_WEIGHT = 0.2;
    private static final double COST_WEIGHT = 0.2;
    private static final double EFFORT_WEIGHT = 0.1;

    private static final int MAX_EXPECTED_MINUTES = 90;
    private static final int MAX_EXPECTED_COST = 5000;
    private static final int MAX_EXPECTED_TRANSFERS = 5;

    // 높을수록 좋은 점수 (0~1)
    public double calculate(Route route) {
        double timeScore    = 1.0 - normalize(route.totalMinutes(), MAX_EXPECTED_MINUTES);
        double transferScore = 1.0 - normalize(countTransfers(route), MAX_EXPECTED_TRANSFERS);
        double costScore    = 1.0 - normalize(route.totalCostWon(), MAX_EXPECTED_COST);
        double effortScore  = 1.0; // 추후 경사도 데이터 연동 시 개선

        return (timeScore    * TIME_WEIGHT)
             + (transferScore * TRANSFER_WEIGHT)
             + (costScore    * COST_WEIGHT)
             + (effortScore  * EFFORT_WEIGHT);
    }

    private double normalize(double value, double max) {
        return Math.min(value / max, 1.0);
    }

    private long countTransfers(Route route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == LegType.TRANSIT)
                .count() - 1;
    }
}
```

**Step 3: 테스트 통과 확인**

```bash
./gradlew :application:test
```
Expected: PASS

**Step 4: Commit**

```bash
git add .
git commit -m "feat: RouteScoreCalculator 구현 (경로 점수 계산)"
```

---

## Task 8: RouteOptimizationService 구현 (핵심 알고리즘)

**Files:**
- Create: `application/src/main/java/com/blackmamba/navigation/application/route/RouteOptimizationService.java`
- Create: `application/src/main/java/com/blackmamba/navigation/application/route/port/TransitRoutePort.java`
- Create: `application/src/main/java/com/blackmamba/navigation/application/route/port/MobilityTimePort.java`
- Create: `application/src/main/java/com/blackmamba/navigation/application/route/port/MobilityAvailabilityPort.java`
- Test: `application/src/test/java/com/blackmamba/navigation/application/route/RouteOptimizationServiceTest.java`

**Step 1: Port 인터페이스 정의 (의존성 역전)**

```java
// TransitRoutePort.java
package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import reactor.core.publisher.Mono;
import java.util.List;

public interface TransitRoutePort {
    Mono<List<Leg>> getTransitRoute(Location origin, Location destination);
    Mono<Integer> getTransitTimeMinutes(Location origin, Location destination);
}
```

```java
// MobilityTimePort.java
package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import reactor.core.publisher.Mono;

public interface MobilityTimePort {
    Mono<Integer> getMobilityTimeMinutes(Location origin, Location destination, MobilityType type);
}
```

```java
// MobilityAvailabilityPort.java
package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.route.MobilityInfo;
import com.blackmamba.navigation.domain.route.MobilityType;
import reactor.core.publisher.Mono;
import java.util.Optional;

public interface MobilityAvailabilityPort {
    Mono<Optional<MobilityInfo>> findNearbyMobility(double lat, double lng, MobilityType type);
}
```

**Step 2: 테스트 먼저 작성 (Port Mock 기반)**

```java
// RouteOptimizationServiceTest.java
package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteOptimizationServiceTest {

    @Mock TransitRoutePort transitRoutePort;
    @Mock MobilityTimePort mobilityTimePort;
    @Mock MobilityAvailabilityPort mobilityAvailabilityPort;
    @Mock CandidatePointSelector candidatePointSelector;
    @Mock RouteScoreCalculator scoreCalculator;

    @InjectMocks RouteOptimizationService service;

    @Test
    void 기본_대중교통_경로를_항상_포함한다() {
        Location origin = new Location("서울역", 37.5547, 126.9706);
        Location destination = new Location("강남역", 37.4979, 127.0276);

        Leg transitLeg = new Leg(LegType.TRANSIT, "BUS", 45, 10000, origin, destination, null, null);
        when(transitRoutePort.getTransitRoute(any(), any()))
                .thenReturn(Mono.just(List.of(transitLeg)));
        when(candidatePointSelector.select(any(), any())).thenReturn(List.of());
        when(scoreCalculator.calculate(any())).thenReturn(0.5);

        List<Route> routes = service.findRoutes(origin, destination, List.of()).block();

        assertThat(routes).isNotEmpty();
        assertThat(routes.stream().anyMatch(r -> r.type() == RouteType.TRANSIT_ONLY)).isTrue();
    }

    @Test
    void 이동수단_조합_경로가_더_빠르면_추천으로_표시된다() {
        Location origin = new Location("서울역", 37.5547, 126.9706);
        Location destination = new Location("강남역", 37.4979, 127.0276);
        Location candidate = new Location("중간역", 37.5200, 127.0000);

        Leg transitLeg = new Leg(LegType.TRANSIT, "BUS", 45, 10000, origin, destination, null, null);
        when(transitRoutePort.getTransitRoute(any(), any()))
                .thenReturn(Mono.just(List.of(transitLeg)));
        when(transitRoutePort.getTransitTimeMinutes(any(), any()))
                .thenReturn(Mono.just(18));
        when(mobilityTimePort.getMobilityTimeMinutes(any(), any(), any()))
                .thenReturn(Mono.just(9)); // 18+9=27분 < 45분
        when(mobilityAvailabilityPort.findNearbyMobility(any(Double.class), any(Double.class), any()))
                .thenReturn(Mono.just(Optional.of(
                        new MobilityInfo(MobilityType.KICKBOARD_SHARED, "씽씽",
                                "DEV_001", 85, null, 37.52, 127.0, 0, 120))));
        when(candidatePointSelector.select(any(), any())).thenReturn(List.of(candidate));
        when(scoreCalculator.calculate(any())).thenReturn(0.8, 0.5); // 조합 경로가 더 높은 점수

        List<Route> routes = service.findRoutes(origin, destination,
                List.of(MobilityType.KICKBOARD_SHARED)).block();

        assertThat(routes.get(0).recommended()).isTrue();
        assertThat(routes.get(0).totalMinutes()).isEqualTo(27);
    }
}
```

**Step 3: RouteOptimizationService 구현**

```java
// RouteOptimizationService.java
package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

@Service
public class RouteOptimizationService {

    private final TransitRoutePort transitRoutePort;
    private final MobilityTimePort mobilityTimePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final CandidatePointSelector candidatePointSelector;
    private final RouteScoreCalculator scoreCalculator;

    public RouteOptimizationService(TransitRoutePort transitRoutePort,
                                     MobilityTimePort mobilityTimePort,
                                     MobilityAvailabilityPort mobilityAvailabilityPort,
                                     CandidatePointSelector candidatePointSelector,
                                     RouteScoreCalculator scoreCalculator) {
        this.transitRoutePort = transitRoutePort;
        this.mobilityTimePort = mobilityTimePort;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
        this.candidatePointSelector = candidatePointSelector;
        this.scoreCalculator = scoreCalculator;
    }

    public Mono<List<Route>> findRoutes(Location origin, Location destination,
                                         List<MobilityType> availableMobility) {
        return transitRoutePort.getTransitRoute(origin, destination)
                .flatMap(baseLegs -> {
                    Route baseRoute = Route.of(baseLegs, RouteType.TRANSIT_ONLY);
                    int baseMinutes = baseRoute.totalMinutes();

                    if (availableMobility.isEmpty()) {
                        double score = scoreCalculator.calculate(baseRoute);
                        return Mono.just(List.of(baseRoute.withScore(score, true)));
                    }

                    return generateCombinedRoutes(baseLegs, origin, destination, availableMobility)
                            .collectList()
                            .map(combinedRoutes -> rankRoutes(baseRoute, combinedRoutes, baseMinutes));
                });
    }

    private Flux<Route> generateCombinedRoutes(List<Leg> baseLegs, Location origin,
                                                Location destination,
                                                List<MobilityType> mobilityTypes) {
        return Flux.fromIterable(mobilityTypes)
                .flatMap(mobilityType -> {
                    MobilityConfig config = mobilityType == MobilityType.KICKBOARD_SHARED
                            ? MobilityConfig.kickboard() : MobilityConfig.bike();
                    List<Location> candidates = candidatePointSelector.select(baseLegs, config);

                    return Flux.fromIterable(candidates)
                            .flatMap(candidate -> buildCombinedRoute(
                                    origin, candidate, destination, mobilityType, baseLegs));
                });
    }

    private Mono<Route> buildCombinedRoute(Location origin, Location switchPoint,
                                            Location destination, MobilityType mobilityType,
                                            List<Leg> baseLegs) {
        Mono<Integer> transitTime = transitRoutePort.getTransitTimeMinutes(origin, switchPoint);
        Mono<Integer> mobilityTime = mobilityTimePort.getMobilityTimeMinutes(
                switchPoint, destination, mobilityType);
        Mono<Optional<MobilityInfo>> mobilityAvailability =
                mobilityAvailabilityPort.findNearbyMobility(
                        switchPoint.lat(), switchPoint.lng(), mobilityType);

        return Mono.zip(transitTime, mobilityTime, mobilityAvailability)
                .filter(tuple -> tuple.getT3().isPresent())
                .map(tuple -> {
                    int totalMinutes = tuple.getT1() + tuple.getT2();
                    MobilityInfo mobilityInfo = tuple.getT3().get();

                    Leg mobilityLeg = new Leg(
                            mobilityType == MobilityType.KICKBOARD_SHARED ? LegType.KICKBOARD : LegType.BIKE,
                            mobilityType.name(), tuple.getT2(), 0,
                            switchPoint, destination, null, mobilityInfo);

                    List<Leg> combinedLegs = buildCombinedLegs(baseLegs, switchPoint, mobilityLeg);
                    RouteType routeType = mobilityType == MobilityType.KICKBOARD_SHARED
                            ? RouteType.TRANSIT_WITH_KICKBOARD : RouteType.TRANSIT_WITH_BIKE;

                    return Route.of(combinedLegs, routeType);
                });
    }

    private List<Leg> buildCombinedLegs(List<Leg> baseLegs, Location switchPoint, Leg mobilityLeg) {
        List<Leg> result = new ArrayList<>();
        for (Leg leg : baseLegs) {
            result.add(leg);
            if (leg.end().name().equals(switchPoint.name())) break;
        }
        result.add(mobilityLeg);
        return result;
    }

    private List<Route> rankRoutes(Route baseRoute, List<Route> combinedRoutes, int baseMinutes) {
        List<Route> all = new ArrayList<>();
        all.add(baseRoute);
        all.addAll(combinedRoutes);

        List<Route> scored = all.stream()
                .map(r -> r.withScore(scoreCalculator.calculate(r), false))
                .sorted(Comparator.comparingDouble(Route::score).reversed())
                .limit(5)
                .toList();

        // 1위 추천 표시 + 기존 대비 절약 시간 추가
        List<Route> result = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            Route r = scored.get(i);
            int saved = baseMinutes - r.totalMinutes();
            Route withComparison = r.withComparison(new Comparison(baseMinutes, Math.max(saved, 0)));
            result.add(i == 0 ? withComparison.withScore(withComparison.score(), true) : withComparison);
        }
        return result;
    }
}
```

**Step 4: 테스트 통과 확인**

```bash
./gradlew :application:test
```
Expected: PASS

**Step 5: Commit**

```bash
git add .
git commit -m "feat: RouteOptimizationService 구현 (핵심 멀티모달 경로 탐색 알고리즘)"
```

---

## Task 9: REST API Controller 구현

**Files:**
- Create: `api/src/main/java/com/blackmamba/navigation/api/route/RouteController.java`
- Create: `api/src/main/java/com/blackmamba/navigation/api/route/dto/RouteSearchRequest.java`
- Create: `api/src/main/java/com/blackmamba/navigation/api/route/dto/RouteResponse.java`
- Create: `api/src/main/resources/application.yml`
- Test: `api/src/test/java/com/blackmamba/navigation/api/route/RouteControllerTest.java`

**Step 1: 테스트 먼저 작성**

```java
// RouteControllerTest.java
package com.blackmamba.navigation.api.route;

import com.blackmamba.navigation.application.route.RouteOptimizationService;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RouteController.class)
class RouteControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean RouteOptimizationService routeOptimizationService;

    @Test
    void 경로_탐색_API가_200을_반환한다() throws Exception {
        Location a = new Location("서울역", 37.5547, 126.9706);
        Location b = new Location("강남역", 37.4979, 127.0276);
        Route route = new Route("rt_001", RouteType.TRANSIT_ONLY, 45, 1250,
                0.5, true, List.of(), new Comparison(45, 0));

        when(routeOptimizationService.findRoutes(any(), any(), any()))
                .thenReturn(Mono.just(List.of(route)));

        mockMvc.perform(get("/api/routes")
                        .param("originLat", "37.5547")
                        .param("originLng", "126.9706")
                        .param("destLat", "37.4979")
                        .param("destLng", "127.0276"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].routeId").value("rt_001"))
                .andExpect(jsonPath("$.routes[0].totalMinutes").value(45));
    }
}
```

**Step 2: RouteController 구현**

```java
// RouteController.java
package com.blackmamba.navigation.api.route;

import com.blackmamba.navigation.application.route.RouteOptimizationService;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.domain.route.Route;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private final RouteOptimizationService routeOptimizationService;

    public RouteController(RouteOptimizationService routeOptimizationService) {
        this.routeOptimizationService = routeOptimizationService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> searchRoutes(
            @RequestParam double originLat,
            @RequestParam double originLng,
            @RequestParam double destLat,
            @RequestParam double destLng,
            @RequestParam(defaultValue = "") List<String> mobility
    ) {
        Location origin = new Location("출발지", originLat, originLng);
        Location destination = new Location("목적지", destLat, destLng);
        List<MobilityType> mobilityTypes = mobility.stream()
                .map(MobilityType::valueOf)
                .toList();

        List<Route> routes = routeOptimizationService
                .findRoutes(origin, destination, mobilityTypes)
                .block();

        return ResponseEntity.ok(Map.of("routes", routes));
    }
}
```

**Step 3: api/application.yml 설정**

```yaml
server:
  port: 8080

spring:
  profiles:
    active: local

odsay:
  api-key: ${ODSAY_API_KEY}
ddareungi:
  api-key: ${DDAREUNGI_API_KEY}
tago:
  api-key: ${TAGO_API_KEY}
naver:
  client-id: ${NAVER_CLIENT_ID}
  client-secret: ${NAVER_CLIENT_SECRET}
```

**Step 4: 테스트 통과 확인**

```bash
./gradlew :api:test
```
Expected: PASS

**Step 5: Commit**

```bash
git add .
git commit -m "feat: REST API Controller 구현 (/api/routes)"
```

---

## Task 10: React 프로젝트 셋업

**Files:**
- Create: `frontend/` (React 프로젝트)

**Step 1: React 프로젝트 생성**

```bash
cd /Users/sjw/Desktop/black-mamba
npm create vite@latest frontend -- --template react
cd frontend
npm install
npm install axios react-router-dom
npm install -D tailwindcss postcss autoprefixer
npx tailwindcss init -p
```

**Step 2: TailwindCSS 설정**

```js
// tailwind.config.js
export default {
  content: ["./index.html", "./src/**/*.{js,jsx}"],
  theme: { extend: {} },
  plugins: [],
}
```

```css
/* src/index.css */
@tailwind base;
@tailwind components;
@tailwind utilities;
```

**Step 3: 기본 라우터 설정**

```jsx
// src/main.jsx
import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import App from './App.jsx'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<App />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>
)
```

**Step 4: 개발 서버 실행 확인**

```bash
npm run dev
```
Expected: http://localhost:5173 에서 정상 동작

**Step 5: Commit**

```bash
cd /Users/sjw/Desktop/black-mamba
git add frontend/
git commit -m "feat: React + Vite + TailwindCSS 프론트엔드 프로젝트 셋업"
```

---

## Task 11: 네이버 지도 연동 + 메인 화면

**Files:**
- Create: `frontend/src/hooks/useNaverMap.js`
- Create: `frontend/src/components/map/NaverMap.jsx`
- Create: `frontend/src/components/search/PlaceSearchInput.jsx`
- Create: `frontend/src/components/search/MobilitySelector.jsx`
- Create: `frontend/src/pages/MainPage.jsx`

> 네이버 지도 API 키 발급: https://console.ncloud.com → Application 등록 → Web Dynamic Map 활성화

**Step 1: index.html에 네이버 지도 SDK 추가**

```html
<!-- frontend/index.html <head> 안에 추가 -->
<script type="text/javascript"
  src="https://openapi.map.naver.com/openapi/v3/maps.js?ncpClientId=YOUR_CLIENT_ID&submodules=geocoder">
</script>
```

**Step 2: useNaverMap 훅 구현**

```js
// src/hooks/useNaverMap.js
import { useEffect, useRef } from 'react'

export function useNaverMap(containerId, options = {}) {
  const mapRef = useRef(null)

  useEffect(() => {
    if (!window.naver) return

    const map = new window.naver.maps.Map(containerId, {
      center: new window.naver.maps.LatLng(37.5547, 126.9706),
      zoom: 13,
      ...options
    })
    mapRef.current = map

    return () => { mapRef.current = null }
  }, [containerId])

  const addMarker = (lat, lng, label = '') => {
    if (!mapRef.current) return null
    return new window.naver.maps.Marker({
      position: new window.naver.maps.LatLng(lat, lng),
      map: mapRef.current,
      title: label
    })
  }

  const drawPolyline = (coords, color = '#0052A4') => {
    if (!mapRef.current) return
    new window.naver.maps.Polyline({
      path: coords.map(c => new window.naver.maps.LatLng(c.lat, c.lng)),
      strokeColor: color,
      strokeWeight: 4,
      map: mapRef.current
    })
  }

  return { mapRef, addMarker, drawPolyline }
}
```

**Step 3: NaverMap 컴포넌트 구현**

```jsx
// src/components/map/NaverMap.jsx
import { useNaverMap } from '../../hooks/useNaverMap'

export default function NaverMap({ origin, destination, routes }) {
  const { mapRef, addMarker, drawPolyline } = useNaverMap('naver-map')

  return (
    <div id="naver-map" className="w-full h-96 rounded-lg shadow" />
  )
}
```

**Step 4: MobilitySelector 구현**

```jsx
// src/components/search/MobilitySelector.jsx
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
```

**Step 5: MainPage 구현**

```jsx
// src/pages/MainPage.jsx
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import NaverMap from '../components/map/NaverMap'
import MobilitySelector from '../components/search/MobilitySelector'

export default function MainPage() {
  const [origin, setOrigin] = useState('')
  const [destination, setDestination] = useState('')
  const [mobility, setMobility] = useState([])
  const navigate = useNavigate()

  const handleSearch = () => {
    navigate(`/routes?origin=${origin}&dest=${destination}&mobility=${mobility.join(',')}`)
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-lg mx-auto p-4">
        <h1 className="text-2xl font-bold text-gray-800 mb-4">
          🐍 Black Mamba
        </h1>
        <div className="bg-white rounded-xl shadow p-4 space-y-3">
          <input
            value={origin}
            onChange={e => setOrigin(e.target.value)}
            placeholder="출발지를 입력하세요"
            className="w-full border rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
          />
          <input
            value={destination}
            onChange={e => setDestination(e.target.value)}
            placeholder="목적지를 입력하세요"
            className="w-full border rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
          />
          <MobilitySelector selected={mobility} onChange={setMobility} />
          <button
            onClick={handleSearch}
            className="w-full bg-blue-500 text-white py-2 rounded-lg font-medium hover:bg-blue-600 transition"
          >
            경로 탐색
          </button>
        </div>
        <div className="mt-4">
          <NaverMap />
        </div>
      </div>
    </div>
  )
}
```

**Step 6: 브라우저 확인**

```bash
cd frontend && npm run dev
```
Expected: 메인 화면 정상 렌더링

**Step 7: Commit**

```bash
cd /Users/sjw/Desktop/black-mamba
git add frontend/
git commit -m "feat: 메인 화면 구현 (네이버 지도 + 이동수단 선택)"
```

---

## Task 12: 경로 결과 화면 구현

**Files:**
- Create: `frontend/src/api/routeApi.js`
- Create: `frontend/src/components/route/RouteCard.jsx`
- Create: `frontend/src/components/route/LegItem.jsx`
- Create: `frontend/src/pages/RouteListPage.jsx`

**Step 1: routeApi 구현**

```js
// src/api/routeApi.js
import axios from 'axios'

const BASE_URL = 'http://localhost:8080/api'

export const searchRoutes = async ({ originLat, originLng, destLat, destLng, mobility }) => {
  const { data } = await axios.get(`${BASE_URL}/routes`, {
    params: { originLat, originLng, destLat, destLng, mobility: mobility.join(',') }
  })
  return data.routes
}
```

**Step 2: RouteCard 구현**

```jsx
// src/components/route/RouteCard.jsx
const MOBILITY_EMOJI = {
  TRANSIT: '🚌', WALK: '🚶', BIKE: '🚲', KICKBOARD: '🛴'
}

export default function RouteCard({ route, selected, onClick }) {
  return (
    <div
      onClick={onClick}
      className={`p-4 rounded-xl border cursor-pointer transition
        ${selected ? 'border-blue-500 bg-blue-50' : 'border-gray-200 bg-white'}`}
    >
      <div className="flex justify-between items-start">
        <div>
          {route.recommended && (
            <span className="text-xs bg-blue-500 text-white px-2 py-0.5 rounded-full mr-2">
              ⭐ 추천
            </span>
          )}
          <span className="text-sm text-gray-500">
            {route.legs.map(l => MOBILITY_EMOJI[l.type]).join(' → ')}
          </span>
        </div>
        <span className="text-xl font-bold text-gray-800">{route.totalMinutes}분</span>
      </div>
      {route.comparison?.savedMinutes > 0 && (
        <p className="text-xs text-green-600 mt-1">
          🔥 기존보다 {route.comparison.savedMinutes}분 빠름
        </p>
      )}
      <p className="text-sm text-gray-500 mt-1">
        {route.totalCostWon.toLocaleString()}원
      </p>
    </div>
  )
}
```

**Step 3: RouteListPage 구현**

```jsx
// src/pages/RouteListPage.jsx
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { searchRoutes } from '../api/routeApi'
import RouteCard from '../components/route/RouteCard'
import NaverMap from '../components/map/NaverMap'

export default function RouteListPage() {
  const [searchParams] = useSearchParams()
  const [routes, setRoutes] = useState([])
  const [selectedRoute, setSelectedRoute] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const mobility = searchParams.get('mobility')?.split(',').filter(Boolean) || []
    searchRoutes({
      originLat: 37.5547, originLng: 126.9706,
      destLat: 37.4979, destLng: 127.0276,
      mobility
    }).then(data => {
      setRoutes(data)
      setSelectedRoute(data[0])
    }).finally(() => setLoading(false))
  }, [])

  if (loading) return (
    <div className="flex justify-center items-center h-screen">
      <p className="text-gray-500">경로를 탐색 중입니다...</p>
    </div>
  )

  return (
    <div className="max-w-lg mx-auto p-4">
      <h2 className="text-lg font-bold mb-3">경로 결과</h2>
      <div className="space-y-3 mb-4">
        {routes.map(route => (
          <RouteCard
            key={route.routeId}
            route={route}
            selected={selectedRoute?.routeId === route.routeId}
            onClick={() => setSelectedRoute(route)}
          />
        ))}
      </div>
      <NaverMap selectedRoute={selectedRoute} />
    </div>
  )
}
```

**Step 4: main.jsx 라우트 추가**

```jsx
import RouteListPage from './pages/RouteListPage'
import MainPage from './pages/MainPage'

// Routes 안에 추가:
<Route path="/routes" element={<RouteListPage />} />
```

**Step 5: 브라우저 확인**

```bash
npm run dev
```
Expected: 경로 결과 카드 + 추천 경로 강조 정상 표시

**Step 6: Commit**

```bash
cd /Users/sjw/Desktop/black-mamba
git add frontend/
git commit -m "feat: 경로 결과 화면 구현 (RouteCard + 지도 연동)"
```

---

## Task 13: 백엔드-프론트엔드 통합 확인

**Step 1: 환경변수 설정**

```bash
# /Users/sjw/Desktop/black-mamba/.env (git에 올리지 말 것)
ODSAY_API_KEY=발급받은키
DDAREUNGI_API_KEY=발급받은키
TAGO_API_KEY=발급받은키
NAVER_CLIENT_ID=발급받은키
NAVER_CLIENT_SECRET=발급받은키
```

**Step 2: 백엔드 실행**

```bash
cd /Users/sjw/Desktop/black-mamba
ODSAY_API_KEY=xxx DDAREUNGI_API_KEY=xxx TAGO_API_KEY=xxx \
./gradlew :api:bootRun
```

**Step 3: 프론트엔드 실행**

```bash
cd frontend && npm run dev
```

**Step 4: 통합 시나리오 테스트**

```
1. 메인 화면에서 출발지/목적지 입력
2. 킥보드 선택 후 "경로 탐색" 클릭
3. 결과 화면에서 추천 경로 확인
4. 지도에 경로 폴리라인 표시 확인
```

**Step 5: .gitignore 추가**

```
# .gitignore
.env
*.env.local
frontend/node_modules/
frontend/dist/
build/
.gradle/
```

**Step 6: 최종 Commit**

```bash
git add .gitignore
git commit -m "chore: .gitignore 추가 및 통합 확인 완료"
```

---

## API 키 발급 체크리스트

| API | 발급처 | 비고 |
|-----|--------|------|
| ODsay | https://lab.odsay.com | 무료 |
| 따릉이 | https://data.seoul.go.kr | 무료 |
| 국토부 TAGO | https://www.data.go.kr | 무료 |
| 네이버 지도 | https://console.ncloud.com | 월 100만 호출 무료 |
