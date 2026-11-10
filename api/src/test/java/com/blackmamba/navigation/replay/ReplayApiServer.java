package com.blackmamba.navigation.replay;

import com.blackmamba.navigation.infra.common.http.ExternalApiJson;
import com.blackmamba.navigation.infra.odsay.dto.OdsayRouteResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 외부 API 3종(ODsay·따릉이·Tmap) 자리에 서는 로컬 서버.
 * <ul>
 *   <li><b>재생</b>: 요청을 "무엇을 물었는가" 로 이름 붙인 파일에서 응답을 돌려준다. 파일이 없으면 500 을 주고 기록해 둔다 —
 *       코드가 바뀌어 새 조회가 생겼다는 뜻이라 테스트가 그 목록과 함께 실패한다</li>
 *   <li><b>녹화</b>: 파일이 없으면 실제 API 를 호출해 응답을 저장한 뒤 돌려준다. 실제 키는 요청을 실제 API 로 넘길 때만 쓰고
 *       파일 이름·내용에는 남기지 않는다</li>
 * </ul>
 * 파일 이름에 좌표가 그대로 들어가므로, 같은 출발·도착이면 언제 돌려도 같은 파일을 읽는다.
 */
final class ReplayApiServer implements AutoCloseable {

    static final String ODSAY_PREFIX = "/odsay";
    static final String DDAREUNGI_PREFIX = "/ddareungi";
    static final String TMAP_PREFIX = "/tmap";

    private static final Pattern BIKE_LIST_PATTERN = Pattern.compile("/json/bikeList/(\\d+)/(\\d+)/?$");
    private static final Pattern JSON_FIELD_PATTERN = Pattern.compile("\"(startX|startY|endX|endY)\"\\s*:\\s*\"([^\"]+)\"");

    /** 녹화할 때만 쓰는 실제 API 키. 재생에서는 null. */
    record Upstream(String odsayApiKey, String ddareungiApiKey, String tmapAppKey) {}

    private final HttpServer server;
    private final Path fixtureDir;
    private final Upstream upstream;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Set<String> missingFixtures = new ConcurrentSkipListSet<>();
    private final Set<String> recordedFixtures = new ConcurrentSkipListSet<>();
    private final Set<String> rejectedResponses = new ConcurrentSkipListSet<>();

    static ReplayApiServer replaying(Path fixtureDir) {
        return new ReplayApiServer(fixtureDir, null);
    }

    static ReplayApiServer recording(Path fixtureDir, Upstream upstream) {
        return new ReplayApiServer(fixtureDir, upstream);
    }

    private ReplayApiServer(Path fixtureDir, Upstream upstream) {
        this.fixtureDir = fixtureDir;
        this.upstream = upstream;
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext(ODSAY_PREFIX, this::handleOdsay);
        server.createContext(DDAREUNGI_PREFIX, this::handleDdareungi);
        server.createContext(TMAP_PREFIX, this::handleTmap);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 지난번 확인 뒤로 파일이 없어 답하지 못한 조회 — 비어 있어야 재생 결과를 믿을 수 있다. 읽으면 비운다(다음 테스트에 섞이지 않게). */
    Set<String> drainMissingFixtures() {
        return drain(missingFixtures);
    }

    /** 지난번 확인 뒤로 녹화 중 실제 API 가 실패로 답해 저장하지 않은 조회. 읽으면 비운다. */
    Set<String> drainRejectedResponses() {
        return drain(rejectedResponses);
    }

    private static Set<String> drain(Set<String> names) {
        Set<String> drained = new TreeSet<>(names);
        names.removeAll(drained);
        return drained;
    }

    Set<String> recordedFixtures() {
        return new TreeSet<>(recordedFixtures);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ── ODsay: GET /odsay/searchPubTransPathT?apiKey=..&SX=..&SY=..&EX=..&EY=..

    private void handleOdsay(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String name = "odsay/" + query.get("SX") + "_" + query.get("SY") + "_" + query.get("EX") + "_" + query.get("EY") + ".json";
        respond(exchange, name, ReplayApiServer::isRecordableOdsay, () -> {
            String url = "https://api.odsay.com/v1/api/searchPubTransPathT?apiKey=" + encode(upstream.odsayApiKey())
                    + "&SX=" + query.get("SX") + "&SY=" + query.get("SY") + "&EX=" + query.get("EX") + "&EY=" + query.get("EY");
            return HttpRequest.newBuilder(URI.create(url)).GET();
        });
    }

    // ── 따릉이: GET /ddareungi/{key}/json/bikeList/{start}/{end}/

    private void handleDdareungi(HttpExchange exchange) throws IOException {
        Matcher matcher = BIKE_LIST_PATTERN.matcher(exchange.getRequestURI().getPath());
        if (!matcher.find()) {
            write(exchange, 404, "{}");
            return;
        }
        String start = matcher.group(1);
        String end = matcher.group(2);
        respond(exchange, "ddareungi/bikeList_" + start + "_" + end + ".json", ReplayApiServer::isRecordableDdareungi, () -> {
            String url = "http://openapi.seoul.go.kr:8088/" + upstream.ddareungiApiKey() + "/json/bikeList/" + start + "/" + end + "/";
            return HttpRequest.newBuilder(URI.create(url)).GET();
        });
    }

    // ── Tmap: POST /tmap/tmap/routes/pedestrian?version=1  {"startX":..,"startY":..,"endX":..,"endY":..}

    private void handleTmap(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = JSON_FIELD_PATTERN.matcher(body);
        while (matcher.find()) {
            fields.put(matcher.group(1), matcher.group(2));
        }
        String name = "tmap/" + fields.get("startX") + "_" + fields.get("startY") + "_" + fields.get("endX") + "_" + fields.get("endY") + ".json";
        respond(exchange, name, response -> response.contains("\"features\""), () -> HttpRequest.newBuilder(URI.create("https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1"))
                .header("appKey", upstream.tmapAppKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    // ── 공통

    private interface UpstreamRequest {
        HttpRequest.Builder build();
    }

    /**
     * ODsay 는 한도 초과·키 오류도, "경로 없음" 도 HTTP 200 의 error 봉투로 준다. 경로 없음은 운영에서도 정상 결과(빈 경로)라 녹화하고,
     * 나머지 오류는 저장하지 않는다. 판정은 운영 코드와 같은 파서로 한다.
     */
    private static boolean isRecordableOdsay(String body) {
        try {
            return ExternalApiJson.MAPPER.readValue(body, OdsayRouteResponse.class)
                    .findApiError()
                    .map(OdsayRouteResponse.ApiError::isNoRoute)
                    .orElse(true);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 따릉이 키 오류는 HTTP 200 의 XML 이다. 마지막 쪽 너머(INFO-200, 데이터 없음)는 정상 응답이다. */
    private static boolean isRecordableDdareungi(String body) {
        return body.contains("\"rentBikeStatus\"") || body.contains("INFO-200");
    }

    /**
     * @param isRecordable 저장해도 되는 응답인가 — 상태 코드만으로는 걸러지지 않는 실패가 있다
     */
    private void respond(HttpExchange exchange, String fixtureName, Predicate<String> isRecordable, UpstreamRequest upstreamRequest) throws IOException {
        Path file = fixtureDir.resolve(fixtureName);
        if (Files.exists(file)) {
            write(exchange, 200, Files.readString(file));
            return;
        }
        if (upstream == null) {
            missingFixtures.add(fixtureName);
            write(exchange, 500, "{\"replay\":\"녹화된 응답 없음: " + fixtureName + "\"}");
            return;
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    upstreamRequest.build().timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !isRecordable.test(response.body())) {
                // 실패 응답은 저장하지 않는다 — 다음 녹화에서 다시 시도할 수 있게
                rejectedResponses.add(fixtureName + " (HTTP " + response.statusCode() + ")");
                write(exchange, response.statusCode(), response.body());
                return;
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, response.body());
            recordedFixtures.add(fixtureName);
            write(exchange, 200, response.body());
        } catch (IOException e) {
            // 실제 API 에 닿지 못했다 — 연결을 끊어 버리면 클라이언트가 추정값으로 대체하고 그 결과가 기대값이 된다
            rejectedResponses.add(fixtureName + " (" + e.getClass().getSimpleName() + ")");
            write(exchange, 502, "{}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rejectedResponses.add(fixtureName + " (interrupted)");
            write(exchange, 500, "{}");
        }
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0) {
                query.put(pair.substring(0, index), URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
            }
        }
        return query;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
