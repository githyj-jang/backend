package com.softbank.back.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softbank.back.infra.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TerraformService {

    @Value("${terraform.base.path:src/main/resources/terraform}")
    private String terraformBasePath;

    @Value("${terraform.workspace.path:./terraform-workspaces}")
    private String workspacePath;

    // 세션별 실행 컨텍스트 관리
    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TerraformBackendService backendService;

    public TerraformService(TerraformBackendService backendService) {
        this.backendService = backendService;
    }

    /**
     * FR-01: 비동기 인프라 배포 시작
     * PUT 메서드로 호출 - 기존 인프라가 있으면 업데이트, 없으면 생성
     */
    public CompletableFuture<InfraResponse> applyInfrastructure(TerraformRequest request) {
        String sessionId = request.getSessionId();

        // 세션 컨텍스트 확인 또는 생성
        SessionContext context = sessions.computeIfAbsent(sessionId, id -> {
            String sessionDir = createSessionWorkspace(id);
            return new SessionContext(id, sessionDir);
        });

        // 이미 실행 중인 작업이 있는지 확인
        if (context.getCurrentTask() != null && !context.getCurrentTask().isDone()) {
            log.warn("Session {} already has a task in progress", sessionId);
            throw new IllegalStateException("Provisioning already in progress for session: " + sessionId);
        }

        context.setRequest(request);
        context.updateStatus(InfraStatus.INIT, 0, "Initializing Terraform...");

        // 비동기 작업 시작
        CompletableFuture<InfraResponse> task = CompletableFuture.supplyAsync(() -> {
            try {
                return executeTerrformApply(context);
            } catch (Exception e) {
                log.error("Terraform apply failed for session {}", sessionId, e);
                context.updateStatus(InfraStatus.FAILED, 0, "Error: " + e.getMessage());
                throw new RuntimeException("Terraform apply failed", e);
            }
        });

        context.setCurrentTask(task.thenAccept(r -> {}));
        return task;
    }

    /**
     * FR-04: 리소스 파괴
     */
    public CompletableFuture<String> destroyInfrastructure(String sessionId) {
        SessionContext context = sessions.get(sessionId);
        if (context == null) {
            throw new IllegalStateException("No infrastructure found for session: " + sessionId);
        }

        // 이미 실행 중인 작업이 있는지 확인
        if (context.getCurrentTask() != null && !context.getCurrentTask().isDone()) {
            throw new IllegalStateException("Another operation is in progress for session: " + sessionId);
        }

        context.updateStatus(InfraStatus.DESTROYING, 0, "Starting terraform destroy...");

        CompletableFuture<String> task = CompletableFuture.supplyAsync(() -> {
            try {
                return executeTerraformDestroy(context);
            } catch (Exception e) {
                log.error("Terraform destroy failed for session {}", sessionId, e);
                context.updateStatus(InfraStatus.FAILED, 0, "Destroy failed: " + e.getMessage());
                throw new RuntimeException("Terraform destroy failed", e);
            }
        });

        context.setCurrentTask(task.thenAccept(r -> {}));
        return task;
    }

    /**
     * FR-02: 실시간 상태 조회
     */
    public ProvisioningLog getStatus(String sessionId) {
        SessionContext context = sessions.get(sessionId);
        if (context == null) {
            return new ProvisioningLog(sessionId, InfraStatus.INIT, 0, "No session found", LocalDateTime.now());
        }

        return new ProvisioningLog(
                sessionId,
                context.getStatus(),
                context.getProgressPercentage(),
                context.getLatestLog(),
                context.getLastUpdated()
        );
    }

    /**
     * FR-03: 인프라 정보 조회 (Terraform outputs)
     */
    public InfraResponse getInfrastructureInfo(String sessionId) throws Exception {
        SessionContext context = sessions.get(sessionId);
        if (context == null) {
            throw new IllegalStateException("No infrastructure found for session: " + sessionId);
        }

        Map<String, Object> outputs = parseTerraformOutputs(context.getWorkingDirectory());

        return new InfraResponse(
                sessionId,
                context.getStatus(),
                outputs,
                "Infrastructure information retrieved"
        );
    }

    /**
     * Terraform apply 실행
     */
    private InfraResponse executeTerrformApply(SessionContext context) throws Exception {
        String workDir = context.getWorkingDirectory();
        String sessionId = context.getSessionId();
        TerraformRequest request = context.getRequest();

        // 0. ⭐ Backend 리소스 확인 및 생성 (S3/DynamoDB)
        context.updateStatus(InfraStatus.INIT, 5, "Ensuring Terraform backend resources exist...");
        try {
            backendService.ensureBackendResourcesExist();
            log.info("✅ Backend resources are ready for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to initialize backend resources", e);
            throw new RuntimeException("Backend initialization failed: " + e.getMessage(), e);
        }

        // 1. Terraform 파일 복사
        context.updateStatus(InfraStatus.INIT, 10, "Copying terraform files...");
        copyTerraformFiles(workDir);

        // 2. tfvars 파일 생성
        context.updateStatus(InfraStatus.INIT, 15, "Creating terraform.tfvars...");
        createTfvarsFile(workDir, request);

        // 3. ⭐ Terraform init (Backend 연결)
        context.updateStatus(InfraStatus.INIT, 20, "Running terraform init...");
        try {
            runCommand(workDir, "terraform", "init", "-input=false");
            log.info("✅ Terraform initialized for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Terraform init failed for session: {}", sessionId, e);
            throw new RuntimeException("Terraform init failed. Backend resources may not be accessible.", e);
        }

        // 4. ⭐ Workspace 생성 또는 선택
        context.updateStatus(InfraStatus.INIT, 25, "Setting up terraform workspace...");
        try {
            ensureWorkspaceExists(workDir, sessionId);
            log.info("✅ Workspace '{}' is ready", sessionId);
        } catch (Exception e) {
            log.error("Workspace setup failed for session: {}", sessionId, e);
            throw new RuntimeException("Workspace setup failed: " + e.getMessage(), e);
        }

        // 5. Terraform plan
        context.updateStatus(InfraStatus.PLANNING, 40, "Running terraform plan...");
        runCommand(workDir, "terraform", "plan", "-out=tfplan", "-input=false");

        // 6. Terraform apply
        context.updateStatus(InfraStatus.APPLYING, 60, "Running terraform apply...");
        runCommand(workDir, "terraform", "apply", "-input=false", "-auto-approve", "tfplan");

        // 7. Outputs 파싱
        context.updateStatus(InfraStatus.COMPLETE, 100, "Infrastructure provisioning completed!");
        Map<String, Object> outputs = parseTerraformOutputs(workDir);

        return new InfraResponse(
                context.getSessionId(),
                InfraStatus.COMPLETE,
                outputs,
                "Infrastructure successfully provisioned"
        );
    }

    /**
     * ⭐ Terraform Workspace 생성 또는 선택
     * 각 세션은 독립적인 workspace를 사용하여 state를 격리합니다.
     */
    private void ensureWorkspaceExists(String workDir, String sessionId) throws IOException, InterruptedException {
        // 1. 현재 workspace 목록 확인
        String workspaceList = runCommand(workDir, "terraform", "workspace", "list");
        log.debug("Current workspaces:\n{}", workspaceList);

        // 2. sessionId workspace가 있는지 확인
        boolean workspaceExists = workspaceList.contains(sessionId);

        if (workspaceExists) {
            // Workspace가 이미 있으면 선택
            log.info("Selecting existing workspace: {}", sessionId);
            runCommand(workDir, "terraform", "workspace", "select", sessionId);
        } else {
            // Workspace가 없으면 새로 생성
            log.info("Creating new workspace: {}", sessionId);
            runCommand(workDir, "terraform", "workspace", "new", sessionId);
        }

        // 3. 현재 workspace 확인
        String currentWorkspace = runCommand(workDir, "terraform", "workspace", "show").trim();
        log.info("Current workspace: {}", currentWorkspace);

        if (!currentWorkspace.equals(sessionId)) {
            throw new IllegalStateException(
                    String.format("Failed to switch to workspace '%s'. Current workspace: '%s'",
                            sessionId, currentWorkspace)
            );
        }
    }

    /**
     * Terraform destroy 실행
     */
    private String executeTerraformDestroy(SessionContext context) throws Exception {
        String workDir = context.getWorkingDirectory();
        String sessionId = context.getSessionId();

        // 1. Workspace 선택
        context.updateStatus(InfraStatus.DESTROYING, 10, "Selecting workspace...");
        try {
            runCommand(workDir, "terraform", "workspace", "select", sessionId);
        } catch (Exception e) {
            log.warn("Failed to select workspace {}, it may not exist", sessionId);
        }

        // 2. Terraform destroy 실행
        context.updateStatus(InfraStatus.DESTROYING, 30, "Running terraform destroy...");
        runCommand(workDir, "terraform", "destroy", "-auto-approve", "-input=false");

        // 3. Workspace 삭제 (선택사항)
        context.updateStatus(InfraStatus.DESTROYING, 80, "Cleaning up workspace...");
        try {
            // default workspace로 전환 후 삭제
            runCommand(workDir, "terraform", "workspace", "select", "default");
            runCommand(workDir, "terraform", "workspace", "delete", sessionId);
            log.info("Workspace '{}' deleted", sessionId);
        } catch (Exception e) {
            log.warn("Failed to delete workspace {}: {}", sessionId, e.getMessage());
        }

        context.updateStatus(InfraStatus.COMPLETE, 100, "Infrastructure destroyed successfully");

        // 세션 제거
        sessions.remove(sessionId);

        // 작업 디렉토리 삭제
        deleteDirectory(new File(workDir));

        return "Infrastructure destroyed successfully";
    }

    /**
     * 세션별 작업 디렉토리 생성
     */
    private String createSessionWorkspace(String sessionId) {
        try {
            Path sessionPath = Paths.get(workspacePath, sessionId);
            Files.createDirectories(sessionPath);
            return sessionPath.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create workspace for session: " + sessionId, e);
        }
    }

    /**
     * Terraform 파일들을 세션 작업 디렉토리로 복사
     */
    private void copyTerraformFiles(String targetDir) throws IOException {
        Path sourcePath = Paths.get(terraformBasePath);
        Path targetPath = Paths.get(targetDir);

        // ⭐ 필수 파일 존재 확인
        validateRequiredTerraformFiles(sourcePath);

        try (var stream = Files.walk(sourcePath)) {
            stream.filter(source -> !Files.isDirectory(source))
                    .filter(source -> source.toString().endsWith(".tf") ||
                                    source.toString().endsWith(".py") ||
                                    source.toString().endsWith(".sh"))
                    .forEach(source -> {
                        try {
                            Path dest = targetPath.resolve(sourcePath.relativize(source));
                            Files.createDirectories(dest.getParent());
                            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }

        log.info("✅ Terraform files copied to: {}", targetDir);
    }

    /**
     * ⭐ 필수 Terraform 파일 존재 확인
     */
    private void validateRequiredTerraformFiles(Path sourcePath) throws FileNotFoundException {
        String[] requiredFiles = {
                "provider.tf",
                "variables.tf",
                "backend.tf",
                "vpc.tf",
                "ec2.tf",
                "lambda/alarm_processor.py"
        };

        for (String file : requiredFiles) {
            Path filePath = sourcePath.resolve(file);
            if (!Files.exists(filePath)) {
                log.error("❌ Required Terraform file not found: {}", filePath);
                throw new FileNotFoundException(
                        "Required Terraform file not found: " + file +
                        ". Please check the terraform directory structure.");
            }
        }

        log.debug("✅ All required Terraform files exist");
    }

    /**
     * terraform.tfvars 파일 생성
     */
    private void createTfvarsFile(String workDir, TerraformRequest request) throws IOException {
        StringBuilder tfvars = new StringBuilder();
        tfvars.append(String.format("session_id = \"%s\"%n", request.getSessionId()));
        tfvars.append(String.format("aws_region = \"%s\"%n", request.getAwsRegion()));
        tfvars.append(String.format("project_name = \"%s\"%n", request.getProjectName()));
        tfvars.append(String.format("environment = \"%s\"%n", request.getEnvironment()));
        tfvars.append(String.format("ec2_instance_type = \"%s\"%n", request.getEc2InstanceType()));

        if (request.getEc2KeyName() != null && !request.getEc2KeyName().isEmpty()) {
            tfvars.append(String.format("ec2_key_name = \"%s\"%n", request.getEc2KeyName()));
        }

        if (request.getAlertEmail() != null && !request.getAlertEmail().isEmpty()) {
            tfvars.append(String.format("alert_email = \"%s\"%n", request.getAlertEmail()));
        }

        tfvars.append(String.format("cpu_warning_threshold = %d%n", request.getCpuWarningThreshold()));
        tfvars.append(String.format("cpu_critical_threshold = %d%n", request.getCpuCriticalThreshold()));
        tfvars.append(String.format("error_rate_warning_threshold = %d%n", request.getErrorRateWarningThreshold()));
        tfvars.append(String.format("error_rate_critical_threshold = %d%n", request.getErrorRateCriticalThreshold()));
        tfvars.append(String.format("latency_warning_threshold = %d%n", request.getLatencyWarningThreshold()));
        tfvars.append(String.format("latency_critical_threshold = %d%n", request.getLatencyCriticalThreshold()));

        Path tfvarsPath = Paths.get(workDir, "terraform.tfvars");
        Files.writeString(tfvarsPath, tfvars.toString());
    }

    /**
     * Terraform outputs 파싱 (안전한 에러 처리)
     */
    private Map<String, Object> parseTerraformOutputs(String workDir) {
        try {
            String outputJson = runCommand(workDir, "terraform", "output", "-json");

            if (outputJson.trim().isEmpty()) {
                log.warn("⚠️  Terraform outputs are empty for workDir: {}", workDir);
                return new HashMap<>();
            }

            try {
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> rawOutputs =
                        objectMapper.readValue(outputJson, Map.class);

                Map<String, Object> outputs = new HashMap<>();
                rawOutputs.forEach((key, value) -> {
                    if (value != null && value.containsKey("value")) {
                        outputs.put(key, value.get("value"));
                    }
                });

                log.info("✅ Parsed {} Terraform outputs", outputs.size());
                return outputs;

            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.error("❌ Failed to parse Terraform outputs JSON", e);
                log.error("   Raw output: {}", outputJson);
                log.error("   Returning empty outputs map");
                return new HashMap<>();
            }

        } catch (Exception e) {
            log.error("❌ Failed to get Terraform outputs from workDir: {}", workDir, e);
            log.error("   Infrastructure may be provisioned, but outputs cannot be retrieved");
            return new HashMap<>();
        }
    }

    /**
     * 명령어 실행
     */
    private String runCommand(String workDir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.info("[Terraform] {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed with exit code " + exitCode + ": " + output);
        }

        return output.toString();
    }

    /**
     * 디렉토리 삭제
     */
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }

    /**
     * 모든 세션 목록 조회 (관리용)
     */
    public List<ProvisioningLog> getAllSessions() {
        return sessions.values().stream()
                .map(ctx -> new ProvisioningLog(
                        ctx.getSessionId(),
                        ctx.getStatus(),
                        ctx.getProgressPercentage(),
                        ctx.getLatestLog(),
                        ctx.getLastUpdated()
                ))
                .toList();
    }

    /**
     * ⭐ Graceful Shutdown: 서버 종료 시 모든 세션 상태 저장
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("🛑 Graceful shutdown initiated...");

        if (sessions.isEmpty()) {
            log.info("No active sessions to save");
            return;
        }

        // 모든 세션 상태 저장
        sessions.forEach((sessionId, context) -> {
            try {
                context.saveToFile();
                log.info("✅ Saved progress for session: {}", sessionId);
            } catch (Exception e) {
                log.error("❌ Failed to save progress for session: {}", sessionId, e);
            }
        });

        // 실행 중인 Task 대기 (최대 30초)
        sessions.values().stream()
                .filter(ctx -> ctx.getCurrentTask() != null && !ctx.getCurrentTask().isDone())
                .forEach(ctx -> {
                    try {
                        log.info("⏳ Waiting for task to complete: {}", ctx.getSessionId());
                        ctx.getCurrentTask().get(30, java.util.concurrent.TimeUnit.SECONDS);
                        log.info("✅ Task completed: {}", ctx.getSessionId());
                    } catch (java.util.concurrent.TimeoutException e) {
                        log.warn("⏱️ Task timeout for session: {}", ctx.getSessionId());
                    } catch (Exception e) {
                        log.error("❌ Error waiting for task: {}", ctx.getSessionId(), e);
                    }
                });

        log.info("✅ Graceful shutdown completed. Saved {} sessions", sessions.size());
    }
}