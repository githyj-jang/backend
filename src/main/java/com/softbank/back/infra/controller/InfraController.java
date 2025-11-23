package com.softbank.back.infra.controller;

import com.softbank.back.infra.model.InfraResponse;
import com.softbank.back.infra.model.ProvisioningLog;
import com.softbank.back.infra.model.TerraformRequest;
import com.softbank.back.infra.service.TerraformBackendService;
import com.softbank.back.infra.service.TerraformService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 인프라 관리 REST API 컨트롤러
 * 여러 사용자가 세션 ID로 구분되어 각자의 인프라를 관리할 수 있습니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/infra")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // 프론트엔드 연동을 위한 CORS 설정
public class InfraController {

    private final TerraformService terraformService;
    private final TerraformBackendService backendService;

    /**
     * FR-01: 인프라 생성 또는 업데이트 (PUT 방식)
     * PUT 메서드를 사용하여 사용자가 조정 가능한 모든 파라미터를 받아 인프라를 프로비저닝합니다.
     * 기존 인프라가 있으면 업데이트, 없으면 생성합니다.
     *
     * @param request 사용자가 조정 가능한 모든 Terraform 변수
     * @return 비동기 작업 시작 응답
     */
    @PutMapping("/provision")
    public ResponseEntity<Map<String, String>> provisionInfrastructure(@RequestBody TerraformRequest request) {
        log.info(">> [InfraController] Provisioning infrastructure for session: {}", request.getSessionId());

        // 입력 검증
        if (request.getSessionId() == null || request.getSessionId().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Session ID is required",
                    "message", "Please provide a valid session ID"
            ));
        }

        try {
            // 비동기 프로비저닝 시작
            terraformService.applyInfrastructure(request);

            return ResponseEntity.accepted().body(Map.of(
                    "sessionId", request.getSessionId(),
                    "status", "ACCEPTED",
                    "message", "Infrastructure provisioning started. Use /status/{sessionId} to check progress."
            ));
        } catch (IllegalStateException e) {
            log.error("Provisioning failed for session: {}", request.getSessionId(), e);
            return ResponseEntity.status(409).body(Map.of(
                    "error", "CONFLICT",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Unexpected error during provisioning", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "INTERNAL_ERROR",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * FR-02: 실시간 상태 조회
     * 프론트엔드에서 폴링하여 프로비저닝 진행 상황을 확인합니다.
     *
     * @param sessionId 사용자 세션 ID
     * @return 현재 프로비저닝 상태 및 로그
     */
    @GetMapping("/status/{sessionId}")
    public ResponseEntity<ProvisioningLog> getProvisioningStatus(@PathVariable String sessionId) {
        log.debug(">> [InfraController] Status check for session: {}", sessionId);

        try {
            ProvisioningLog status = terraformService.getStatus(sessionId);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to get status for session: {}", sessionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * FR-03: 인프라 정보 조회 (Terraform outputs)
     * 프로비저닝 완료 후 생성된 리소스 정보를 조회합니다.
     *
     * @param sessionId 사용자 세션 ID
     * @return 인프라 리소스 정보 (EC2 IP, S3 버킷 등)
     */
    @GetMapping("/info/{sessionId}")
    public ResponseEntity<InfraResponse> getInfrastructureInfo(@PathVariable String sessionId) {
        log.info(">> [InfraController] Getting infrastructure info for session: {}", sessionId);

        try {
            InfraResponse info = terraformService.getInfrastructureInfo(sessionId);
            return ResponseEntity.ok(info);
        } catch (IllegalStateException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to get infrastructure info for session: {}", sessionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * FR-04: 인프라 파괴
     * 특정 세션의 모든 인프라 리소스를 삭제합니다.
     *
     * @param sessionId 사용자 세션 ID
     * @return 비동기 작업 시작 응답
     */
    @DeleteMapping("/destroy/{sessionId}")
    public ResponseEntity<Map<String, String>> destroyInfrastructure(@PathVariable String sessionId) {
        log.info(">> [InfraController] Destroying infrastructure for session: {}", sessionId);

        try {
            terraformService.destroyInfrastructure(sessionId);

            return ResponseEntity.accepted().body(Map.of(
                    "sessionId", sessionId,
                    "status", "ACCEPTED",
                    "message", "Infrastructure destruction started. Use /status/{sessionId} to check progress."
            ));
        } catch (IllegalStateException e) {
            log.error("Destruction failed for session: {}", sessionId, e);
            return ResponseEntity.status(409).body(Map.of(
                    "error", "CONFLICT",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Unexpected error during destruction", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "INTERNAL_ERROR",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 관리용: 모든 세션 목록 조회
     *
     * @return 모든 세션의 상태 목록
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<ProvisioningLog>> getAllSessions() {
        log.info(">> [InfraController] Getting all sessions");

        try {
            List<ProvisioningLog> sessions = terraformService.getAllSessions();
            return ResponseEntity.ok(sessions);
        } catch (Exception e) {
            log.error("Failed to get all sessions", e);
            return ResponseEntity.internalServerError().build();
        }
    }


    /**
     * 헬스 체크
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "Infrastructure Management Service"
        ));
    }

    /**
     * ⭐ 서버 리소스 상태 조회 (동시 실행 제한 정보)
     * EC2 환경에서 서버 부하를 모니터링하는 데 사용됩니다.
     */
    @GetMapping("/server/resources")
    public ResponseEntity<Map<String, Object>> getServerResourceStatus() {
        log.debug(">> [InfraController] Getting server resource status");

        try {
            Map<String, Object> status = terraformService.getServerResourceStatus();
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to get server resource status", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * ⭐ Terraform Backend 상태 확인
     * S3 버킷과 DynamoDB 테이블이 준비되었는지 확인합니다.
     */
    @GetMapping("/backend/status")
    public ResponseEntity<Map<String, Object>> getBackendStatus() {
        log.info(">> [InfraController] Checking backend status");

        try {
            var backendInfo = backendService.getBackendInfo();

            Map<String, Object> response = new HashMap<>();
            response.put("initialized", backendInfo.initialized());
            response.put("s3Bucket", Map.of(
                    "name", backendInfo.s3Bucket(),
                    "exists", backendInfo.s3Exists(),
                    "region", backendInfo.region()
            ));
            response.put("dynamoTable", Map.of(
                    "name", backendInfo.dynamoTable(),
                    "exists", backendInfo.dynamoExists(),
                    "region", backendInfo.region()
            ));
            response.put("ready", backendService.isBackendReady());

            if (backendService.isBackendReady()) {
                response.put("message", "Terraform backend is ready");
            } else {
                response.put("message", "Backend resources will be created automatically on first provision request");
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to get backend status", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to check backend status",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * ⭐ Terraform Backend 초기화 (수동 트리거)
     * 관리자가 필요 시 Backend 리소스를 수동으로 생성할 수 있습니다.
     */
    @PostMapping("/backend/initialize")
    public ResponseEntity<Map<String, Object>> initializeBackend() {
        log.info(">> [InfraController] Manual backend initialization requested");

        try {
            backendService.ensureBackendResourcesExist();

            var backendInfo = backendService.getBackendInfo();

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Backend resources initialized successfully",
                    "s3Bucket", backendInfo.s3Bucket(),
                    "dynamoTable", backendInfo.dynamoTable(),
                    "region", backendInfo.region()
            ));
        } catch (Exception e) {
            log.error("Failed to initialize backend", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "FAILED",
                    "error", "Backend initialization failed",
                    "message", e.getMessage()
            ));
        }
    }
}