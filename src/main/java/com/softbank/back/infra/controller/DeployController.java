package com.softbank.back.infra.controller;

import com.softbank.back.infra.model.*;
import com.softbank.back.infra.service.TerraformService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 프론트엔드용 간소화된 배포 API 컨트롤러
 * 기본 설정으로 빠르게 인프라를 배포할 수 있습니다.
 */
@Slf4j
@RestController
@RequestMapping("/deploy")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DeployController {

    private final TerraformService terraformService;
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * POST /api/v1/deploy
     * 간소화된 인프라 배포 API (모든 설정 기본값 사용)
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> deploy(@RequestBody(required = false) DeployRequest request) {
        log.info(">> [DeployController] Simple deploy request");

        // sessionId가 없으면 자동 생성
        String sessionId = (request != null && request.getSessionId() != null)
                ? request.getSessionId()
                : "deploy-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("Session ID: {}", sessionId);

        try {
            // 기본 설정으로 TerraformRequest 생성
            TerraformRequest terraformRequest = createDefaultTerraformRequest(sessionId);

            // 기존 TerraformService 활용 (비동기)
            terraformService.applyInfrastructure(terraformRequest);

            return ResponseEntity.accepted().body(Map.of("sessionId", sessionId));

        } catch (IllegalStateException e) {
            log.error("Deploy failed for session: {}", sessionId, e);
            return ResponseEntity.status(409).body(Map.of(
                    "error", "CONFLICT",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Unexpected error during deploy", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "INTERNAL_ERROR",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /api/v1/deploy/status/:sessionId
     * 배포 상태 조회 (프론트엔드 포맷)
     */
    @GetMapping("/status/{sessionId}")
    public ResponseEntity<DeployStatusResponse> getStatus(@PathVariable String sessionId) {
        log.debug(">> [DeployController] Status check for session: {}", sessionId);

        try {
            SessionContext context = terraformService.getSessionContext(sessionId);

            if (context == null) {
                // 세션이 없으면 404
                return ResponseEntity.notFound().build();
            }

            // SessionContext → DeployStatusResponse 변환
            DeployStatusResponse response = DeployStatusResponse.builder()
                    .sessionId(sessionId)
                    .state(context.getStatus().name())
                    .progress(context.getProgressPercentage())
                    .currentStage(context.getLatestLog())
                    .logs(context.getLogs() != null ? context.getLogs() : Collections.singletonList(context.getLatestLog()))
                    .createdAt(context.getCreatedAt() != null ? context.getCreatedAt().format(ISO_FORMATTER) : null)
                    .updatedAt(context.getLastUpdated() != null ? context.getLastUpdated().format(ISO_FORMATTER) : null)
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to get status for session: {}", sessionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * GET /api/v1/deploy/resources/:sessionId
     * 배포된 리소스 정보 조회 (구조화된 포맷)
     */
    @GetMapping("/resources/{sessionId}")
    public ResponseEntity<DeployResourcesResponse> getResources(@PathVariable String sessionId) {
        log.info(">> [DeployController] Getting resources for session: {}", sessionId);

        try {
            InfraResponse infraInfo = terraformService.getInfrastructureInfo(sessionId);

            // 인프라가 완료되지 않았으면 404
            if (infraInfo.getStatus() != InfraStatus.COMPLETE) {
                return ResponseEntity.status(404).build();
            }

            // Terraform outputs → ResourceInfo 변환
            Map<String, Object> outputs = infraInfo.getOutputs();
            ResourceInfo resourceInfo = ResourceInfo.builder()
                    .ec2InstanceId(getStringValue(outputs, "ec2_instance_id"))
                    .ec2PublicIp(getStringValue(outputs, "ec2_public_ip"))
                    .vpcId(getStringValue(outputs, "vpc_id"))
                    .dynamoDbTableName(getStringValue(outputs, "app_data_table_name"))  // 메인 테이블 사용
                    .s3BucketName(getStringValue(outputs, "static_files_bucket_name"))
                    .lambdaFunctionName(getStringValue(outputs, "lambda_function_name"))
                    .snsTopicArn(getStringValue(outputs, "sns_topic_arn"))
                    .build();

            // ⭐ Terraform graph 생성
            String graph = null;
            try {
                graph = terraformService.getTerraformGraph(sessionId);
            } catch (Exception e) {
                log.warn("Failed to generate terraform graph for session: {}", sessionId, e);
                // graph 생성 실패해도 리소스 정보는 반환
            }

            DeployResourcesResponse response = DeployResourcesResponse.builder()
                    .resources(resourceInfo)
                    .graph(graph)  // terraform graph 출력
                    .build();

            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            log.warn("Resources not found for session: {}", sessionId);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to get resources for session: {}", sessionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * DELETE /api/v1/deploy/:sessionId
     * 리소스 전체 삭제 (동기 방식 - 삭제 완료까지 대기)
     * 타임아웃: 10분
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteResources(@PathVariable String sessionId) {
        log.info(">> [DeployController] Deleting resources for session: {} (synchronous mode, 10min timeout)", sessionId);

        try {
            // 비동기 작업 시작
            CompletableFuture<String> future = terraformService.destroyInfrastructure(sessionId);

            // ⭐ 작업이 완료될 때까지 대기 (동기 방식, 최대 10분)
            future.get(10, java.util.concurrent.TimeUnit.MINUTES); // 10분 타임아웃

            log.info("✅ Delete completed successfully for session: {}", sessionId);

            // 삭제 성공 응답 (빈 객체 반환 - 프론트엔드 요청사항)
            return ResponseEntity.ok().body(Map.of(
                    "message", "Resources deleted successfully",
                    "sessionId", sessionId
            ));

        } catch (IllegalStateException e) {
            log.error("Delete failed for session: {}", sessionId, e);
            return ResponseEntity.status(409).body(Map.of(
                    "error", "CONFLICT",
                    "message", e.getMessage()
            ));
        } catch (java.util.concurrent.ExecutionException e) {
            // CompletableFuture 실행 중 에러 발생
            Throwable cause = e.getCause();
            log.error("Delete execution failed for session: {}", sessionId, cause);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "DELETE_FAILED",
                    "message", cause != null ? cause.getMessage() : e.getMessage()
            ));
        } catch (java.util.concurrent.TimeoutException e) {
            log.error("Delete timeout (10 minutes) for session: {}", sessionId, e);
            return ResponseEntity.status(504).body(Map.of(
                    "error", "TIMEOUT",
                    "message", "Delete operation exceeded 10 minutes. Please check the status manually."
            ));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Delete interrupted for session: {}", sessionId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "INTERRUPTED",
                    "message", "Delete operation was interrupted"
            ));
        } catch (Exception e) {
            log.error("Unexpected error during delete", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "INTERNAL_ERROR",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 기본 설정으로 TerraformRequest 생성
     */
    private TerraformRequest createDefaultTerraformRequest(String sessionId) {
        TerraformRequest request = new TerraformRequest();
        request.setSessionId(sessionId);
        request.setAwsRegion("ap-northeast-2");
        request.setProjectName("penguin-land");
        request.setEnvironment("dev");
        request.setEc2InstanceType("t2.micro");
        request.setEc2KeyName("");
        request.setAlertEmail("hoo9268@gmail.com");
        request.setCpuWarningThreshold(50);
        request.setCpuCriticalThreshold(70);
        request.setErrorRateWarningThreshold(3);
        request.setErrorRateCriticalThreshold(5);
        request.setLatencyWarningThreshold(400);
        request.setLatencyCriticalThreshold(700);
        return request;
    }

    /**
     * Map에서 안전하게 String 값 추출
     */
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}

