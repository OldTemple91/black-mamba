package com.blackmamba.navigation.api.rag;

import com.blackmamba.navigation.application.route.RouteHistorySeeder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RAG 벡터 DB 관리 엔드포인트 (데모/시드용).
 * <p>
 * {@code @Profile("!prod")} — 운영 프로파일에서는 빈 자체가 등록되지 않아
 * 인증 없는 시드 엔드포인트가 외부에 노출되지 않는다.
 * 로컬/도커 데모 흐름(README 의 curl seed)은 그대로 동작.
 */
@Tag(name = "RAG 관리", description = "벡터 DB 시드 / 관리용 엔드포인트 (비운영 전용)")
@RestController
@RequestMapping("/api/rag/admin")
@Profile("!prod")
public class RagAdminController {

    private final RouteHistorySeeder routeHistorySeeder;

    public RagAdminController(RouteHistorySeeder routeHistorySeeder) {
        this.routeHistorySeeder = routeHistorySeeder;
    }

    @Operation(
            summary = "경로 이력 시드 적재",
            description = """
                    서울 주요 OD 페어 약 20건을 Qdrant 에 적재한다.
                    데모/발표 전 1회 실행해 "빈 벡터 DB" 상태를 방지한다.

                    실행 전:
                    - Qdrant 가 실행 중이어야 함 (docker compose up -d qdrant)
                    - Ollama 에 bge-m3 모델이 있어야 함 (ollama pull bge-m3)
                    """
    )
    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seedRouteHistory() {
        int count = routeHistorySeeder.seed();
        return ResponseEntity.ok(Map.of(
                "status", count > 0 ? "ok" : "skipped",
                "savedCount", count,
                "message", count > 0
                        ? "시드 완료: 임베딩 작업은 비동기로 Qdrant 에 반영됩니다."
                        : "스킵됨: Qdrant 가 기동되지 않았거나 RouteHistoryPort 빈이 없습니다."
        ));
    }
}
