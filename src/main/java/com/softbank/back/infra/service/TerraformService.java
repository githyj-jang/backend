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
import java.util.concurrent.*;

@Slf4j
@Service
public class TerraformService {

    @Value("${terraform.base.path:src/main/resources/terraform}")
    private String terraformBasePath;

    @Value("${terraform.workspace.path:./terraform-workspaces}")
    private String workspacePath;

    @Value("${terraform.max.concurrent.operations:1}")
    private int maxConcurrentOperations;

    @Value("${terraform.max.queue.size:10}")
    private int maxQueueSize;

    // 세션별 실행 컨텍스트 관리
    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TerraformBackendService backendService;

    // 동시 실행 제한을 위한 Semaphore
    private Semaphore executionSemaphore;

    // 전용 스레드 풀 (제한된 크기)
    private ExecutorService terraformExecutor;

    // 대기 중인 작업 추적
    private final Map<String, CompletableFuture<?>> pendingTasks = new ConcurrentHashMap<>();

    public TerraformService(TerraformBackendService backendService) {
        this.backendService = backendService;
    }

    @jakarta.annotation.PostConstruct
    public void initializeExecutor() {
        // 동시 실행 제한 Semaphore 초기화
        this.executionSemaphore = new Semaphore(maxConcurrentOperations, true); // fair mode

        // 전용 스레드 풀 생성 (최대 큐 크기 제한)
        BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(maxQueueSize);
        this.terraformExecutor = new ThreadPoolExecutor(
            1, // core pool size
            maxConcurrentOperations, // maximum pool size
            60L, TimeUnit.SECONDS, // keep alive time
            workQueue,
            new ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
                @Override
                @SuppressWarnings("NullableProblems")
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    t.setName("terraform-executor-" + counter.incrementAndGet());
                    t.setDaemon(false);
                    return t;
                }
            },
            new ThreadPoolExecutor.AbortPolicy() // 큐가 가득 차면 예외 발생
        );

        log.info("🚀 Terraform Executor initialized: maxConcurrent={}, maxQueueSize={}",
            maxConcurrentOperations, maxQueueSize);

        // 세션 복구는 별도로 실행
        recoverSessions();
    }

    @jakarta.annotation.PreDestroy
    public void shutdownExecutor() {
        log.info("🛑 Shutting down Terraform Executor...");
        if (terraformExecutor != null) {
            terraformExecutor.shutdown();
            try {
                if (!terraformExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    terraformExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                terraformExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * ⭐ 서버 시작 시 세션 복구
     */
    @jakarta.annotation.PostConstruct
    public void recoverSessions() {
        log.info("🔄 Starting session recovery...");

        try {
            Path workspaceRoot = Paths.get(workspacePath);
            if (!Files.exists(workspaceRoot)) {
                log.info("No workspace directory found. Skipping recovery.");
                return;
            }

            int recoveredCount = 0;
            int failedCount = 0;

            try (var stream = Files.list(workspaceRoot)) {
                var sessionDirs = stream.filter(Files::isDirectory).toList();

                for (Path sessionDir : sessionDirs) {
                    try {
                        Path progressFile = sessionDir.resolve(".progress.json");

                        if (Files.exists(progressFile)) {
                            String sessionId = sessionDir.getFileName().toString();
                            log.info("Found session data for: {}", sessionId);

                            SessionContext context = SessionContext.loadFromFile(progressFile);
                            context.setSessionId(sessionId);
                            context.setWorkingDirectory(sessionDir.toAbsolutePath().toString());

                            // 진행 중이던 작업은 FAILED로 변경
                            if (context.getStatus() == InfraStatus.APPLYING ||
                                context.getStatus() == InfraStatus.PLANNING ||
                                context.getStatus() == InfraStatus.INIT) {
                                context.updateStatus(InfraStatus.FAILED,
                                    context.getProgressPercentage(),
                                    "Server restarted during provisioning. You can retry or destroy the resources.");
                            }

                            sessions.put(sessionId, context);
                            recoveredCount++;
                            log.info("✅ Recovered session: {} (status: {})", sessionId, context.getStatus());
                        }
                    } catch (Exception e) {
                        failedCount++;
                        log.error("❌ Failed to recover session from {}", sessionDir, e);
                    }
                }
            }

            log.info("🎉 Session recovery completed: {} recovered, {} failed", recoveredCount, failedCount);

        } catch (Exception e) {
            log.error("❌ Session recovery failed", e);
        }
    }

    /**
     * FR-01: 비동기 인프라 배포 시작 (타임아웃 및 자동 롤백 포함)
     * PUT 메서드로 호출 - 기존 인프라가 있으면 업데이트, 없으면 생성
     * ⭐ Semaphore를 사용한 동시 실행 제한 추가
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

        // 대기열 상태 확인
        int availablePermits = executionSemaphore.availablePermits();
        int queuedTasks = pendingTasks.size();

        if (availablePermits == 0) {
            log.warn("⏳ No available execution slots. Session {} will be queued. Current queue: {}/{}",
                sessionId, queuedTasks, maxQueueSize);
            context.updateStatus(InfraStatus.INIT, 0,
                String.format("Waiting in queue... (%d/%d tasks ahead)", queuedTasks, maxConcurrentOperations));
        } else {
            context.updateStatus(InfraStatus.INIT, 0, "Initializing Terraform...");
        }

        // 비동기 작업 시작 (전용 스레드 풀 사용)
        CompletableFuture<InfraResponse> task = CompletableFuture.supplyAsync(() -> {
            // Semaphore 획득 시도
            boolean acquired = false;
            try {
                log.info("🔒 Session {} waiting for execution permit...", sessionId);
                context.updateStatus(InfraStatus.INIT, 0, "Waiting for available resources...");

                // 최대 30초 대기 (대기 시간 초과 시 예외)
                acquired = executionSemaphore.tryAcquire(30, TimeUnit.SECONDS);

                if (!acquired) {
                    log.error("❌ Session {} failed to acquire execution permit within 30 seconds", sessionId);
                    throw new RuntimeException("Server is too busy. Please try again later.");
                }

                log.info("✅ Session {} acquired execution permit. Starting terraform apply...", sessionId);
                context.updateStatus(InfraStatus.INIT, 0, "Starting Terraform execution...");

                return executeTerrformApply(context);
            } catch (InterruptedException e) {
                log.error("❌ Session {} interrupted while waiting for permit", sessionId, e);
                Thread.currentThread().interrupt();
                context.updateStatus(InfraStatus.FAILED, 0, "Interrupted while waiting");
                throw new RuntimeException("Execution interrupted", e);
            } catch (Exception e) {
                log.error("Terraform apply failed for session {}", sessionId, e);
                context.updateStatus(InfraStatus.FAILED, 0, "Error: " + e.getMessage());
                throw new RuntimeException("Terraform apply failed", e);
            } finally {
                // Semaphore 반환
                if (acquired) {
                    executionSemaphore.release();
                    log.info("🔓 Session {} released execution permit. Available: {}/{}",
                        sessionId, executionSemaphore.availablePermits(), maxConcurrentOperations);
                }
                // 대기 목록에서 제거
                pendingTasks.remove(sessionId);
            }
        }, terraformExecutor) // 전용 스레드 풀 사용
        .orTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
        .exceptionally(ex -> {
            // 타임아웃 또는 에러 발생 시 자동 롤백
            if (ex.getCause() instanceof java.util.concurrent.TimeoutException) {
                log.error("⏱️ Terraform apply timeout (10 minutes) for session: {}", sessionId);
                context.updateStatus(InfraStatus.FAILED, 0, "Timeout: Terraform apply exceeded 10 minutes");
                executeAutoRollback(context);
            } else if (ex.getCause() instanceof RejectedExecutionException) {
                log.error("❌ Task queue is full for session: {}", sessionId);
                context.updateStatus(InfraStatus.FAILED, 0, "Server queue is full. Please try again later.");
            } else {
                log.error("❌ Terraform apply error for session: {}", sessionId, ex);
                executeAutoRollback(context);
            }

            throw new RuntimeException("Provisioning failed and rolled back", ex);
        });

        // 대기 목록에 추가
        pendingTasks.put(sessionId, task);
        context.setCurrentTask(task.thenAccept(r -> {}));
        return task;
    }

    /**
     * FR-04: 리소스 파괴 (개선: 세션이 없어도 작동)
     * ⭐ Semaphore를 사용한 동시 실행 제한 추가
     */
    public CompletableFuture<String> destroyInfrastructure(String sessionId) {
        SessionContext context = sessions.get(sessionId);

        // ⭐ 세션이 없으면 복구 시도
        if (context == null) {
            log.warn("Session {} not found in memory. Attempting to recover or create temporary session...", sessionId);

            // 1. workspace 디렉토리 확인
            Path sessionDir = Paths.get(workspacePath, sessionId);
            if (Files.exists(sessionDir)) {
                // 디렉토리가 있으면 임시 세션 생성
                log.info("Found workspace directory for session: {}. Creating temporary session.", sessionId);
                context = new SessionContext(sessionId, sessionDir.toAbsolutePath().toString());
                context.updateStatus(InfraStatus.DESTROYING, 0, "Recovered session for destruction");
                sessions.put(sessionId, context);
            } else {
                // 디렉토리도 없으면 새로 생성 (Terraform workspace에서 state를 가져올 수 있음)
                log.info("No workspace directory found. Creating new workspace for session: {}", sessionId);
                String newSessionDir = createSessionWorkspace(sessionId);
                context = new SessionContext(sessionId, newSessionDir);
                context.updateStatus(InfraStatus.DESTROYING, 0, "Created workspace to destroy remote resources");
                sessions.put(sessionId, context);
            }
        }

        // 이미 실행 중인 작업이 있는지 확인
        if (context.getCurrentTask() != null && !context.getCurrentTask().isDone()) {
            throw new IllegalStateException("Another operation is in progress for session: " + sessionId);
        }

        // 대기열 상태 확인
        int availablePermits = executionSemaphore.availablePermits();
        int queuedTasks = pendingTasks.size();

        if (availablePermits == 0) {
            log.warn("⏳ No available execution slots. Session {} destroy will be queued. Current queue: {}/{}",
                sessionId, queuedTasks, maxQueueSize);
            context.updateStatus(InfraStatus.DESTROYING, 0,
                String.format("Waiting in queue... (%d/%d tasks ahead)", queuedTasks, maxConcurrentOperations));
        } else {
            context.updateStatus(InfraStatus.DESTROYING, 0, "Starting terraform destroy...");
        }

        final SessionContext finalContext = context;

        CompletableFuture<String> task = CompletableFuture.supplyAsync(() -> {
            // Semaphore 획득 시도
            boolean acquired = false;
            try {
                log.info("🔒 Session {} waiting for execution permit (destroy)...", sessionId);
                finalContext.updateStatus(InfraStatus.DESTROYING, 0, "Waiting for available resources...");

                // 최대 30초 대기
                acquired = executionSemaphore.tryAcquire(30, TimeUnit.SECONDS);

                if (!acquired) {
                    log.error("❌ Session {} failed to acquire execution permit within 30 seconds", sessionId);
                    throw new RuntimeException("Server is too busy. Please try again later.");
                }

                log.info("✅ Session {} acquired execution permit. Starting terraform destroy...", sessionId);
                finalContext.updateStatus(InfraStatus.DESTROYING, 0, "Destroying infrastructure...");

                return executeTerraformDestroy(finalContext);
            } catch (InterruptedException e) {
                log.error("❌ Session {} interrupted while waiting for permit", sessionId, e);
                Thread.currentThread().interrupt();
                finalContext.updateStatus(InfraStatus.FAILED, 0, "Interrupted while waiting");
                throw new RuntimeException("Execution interrupted", e);
            } catch (Exception e) {
                log.error("Terraform destroy failed for session {}", sessionId, e);
                finalContext.updateStatus(InfraStatus.FAILED, 0, "Destroy failed: " + e.getMessage());
                throw new RuntimeException("Terraform destroy failed", e);
            } finally {
                // Semaphore 반환
                if (acquired) {
                    executionSemaphore.release();
                    log.info("🔓 Session {} released execution permit. Available: {}/{}",
                        sessionId, executionSemaphore.availablePermits(), maxConcurrentOperations);
                }
                // 대기 목록에서 제거
                pendingTasks.remove(sessionId);
            }
        }, terraformExecutor) // 전용 스레드 풀 사용
        .exceptionally(ex -> {
            if (ex.getCause() instanceof RejectedExecutionException) {
                log.error("❌ Task queue is full for session: {}", sessionId);
                finalContext.updateStatus(InfraStatus.FAILED, 0, "Server queue is full. Please try again later.");
            }
            throw new RuntimeException("Destroy failed", ex);
        });

        // 대기 목록에 추가
        pendingTasks.put(sessionId, task);
        finalContext.setCurrentTask(task.thenAccept(r -> {}));
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
     * 서버 리소스 상태 조회 (동시 실행 제한 정보)
     */
    public Map<String, Object> getServerResourceStatus() {
        Map<String, Object> status = new HashMap<>();

        // 실행 슬롯 정보
        int availableSlots = executionSemaphore.availablePermits();
        int totalSlots = maxConcurrentOperations;
        int activeSlots = totalSlots - availableSlots;

        status.put("totalExecutionSlots", totalSlots);
        status.put("availableSlots", availableSlots);
        status.put("activeSlots", activeSlots);
        status.put("queuedTasks", pendingTasks.size());
        status.put("maxQueueSize", maxQueueSize);

        // 스레드 풀 정보
        if (terraformExecutor instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) terraformExecutor;
            status.put("poolSize", executor.getPoolSize());
            status.put("activeThreads", executor.getActiveCount());
            status.put("completedTasks", executor.getCompletedTaskCount());
            status.put("queueSize", executor.getQueue().size());
        }

        // 활성 세션 정보
        List<Map<String, Object>> activeSessions = new ArrayList<>();
        for (Map.Entry<String, SessionContext> entry : sessions.entrySet()) {
            SessionContext ctx = entry.getValue();
            if (ctx.getCurrentTask() != null && !ctx.getCurrentTask().isDone()) {
                Map<String, Object> sessionInfo = new HashMap<>();
                sessionInfo.put("sessionId", entry.getKey());
                sessionInfo.put("status", ctx.getStatus());
                sessionInfo.put("progress", ctx.getProgressPercentage());
                activeSessions.add(sessionInfo);
            }
        }
        status.put("activeSessions", activeSessions);

        return status;
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
     * ⭐ Terraform graph 생성 (DOT 형식)
     * terraform graph 명령어 출력을 그대로 반환
     */
    public String getTerraformGraph(String sessionId) throws Exception {
        SessionContext context = sessions.get(sessionId);
        if (context == null) {
            throw new IllegalStateException("No infrastructure found for session: " + sessionId);
        }

        String workDir = context.getWorkingDirectory();

        try {
            // terraform graph 실행 (로그 출력 없이)
            String graph = runCommandSilent(workDir, "terraform", "graph");
            log.debug("Generated terraform graph for session: {}", sessionId);
            return graph;
        } catch (Exception e) {
            log.error("Failed to generate terraform graph for session: {}", sessionId, e);
            throw new RuntimeException("Failed to generate terraform graph", e);
        }
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

        // 5. Terraform plan (State Lock 에러 시 자동 재시도)
        context.updateStatus(InfraStatus.PLANNING, 40, "Running terraform plan...");
        try {
            runCommand(workDir, "terraform", "plan", "-out=tfplan", "-input=false");
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("Error acquiring the state lock")) {
                log.warn("🔒 State lock detected during plan. Attempting to force unlock...");
                handleStateLockError(context, workDir, e);
                // 재시도
                runCommand(workDir, "terraform", "plan", "-out=tfplan", "-input=false");
            } else {
                throw e;
            }
        }

        // 6. Terraform apply (State Lock 에러 시 자동 재시도)
        context.updateStatus(InfraStatus.APPLYING, 60, "Running terraform apply...");
        try {
            runCommand(workDir, "terraform", "apply", "-input=false", "-auto-approve", "tfplan");
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("Error acquiring the state lock")) {
                log.warn("🔒 State lock detected during apply. Attempting to force unlock...");
                handleStateLockError(context, workDir, e);
                // 재시도
                runCommand(workDir, "terraform", "apply", "-input=false", "-auto-approve", "tfplan");
            } else {
                throw e;
            }
        }

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
     * ⭐ FR-01: 자동 롤백 실행 (부분 생성 실패 시)
     * 비동기로 실행하여 메인 스레드를 블록하지 않음
     */
    private void executeAutoRollback(SessionContext context) {
        CompletableFuture.runAsync(() -> {
            try {
                log.warn("🔄 Auto-rollback initiated for session: {}", context.getSessionId());
                context.updateStatus(InfraStatus.DESTROYING, 0, "Auto-rollback: Cleaning up partial resources...");

                String workDir = context.getWorkingDirectory();
                String sessionId = context.getSessionId();

                // 1. Workspace 선택 시도
                try {
                    runCommand(workDir, "terraform", "workspace", "select", sessionId);
                    log.info("✓ Workspace selected: {}", sessionId);
                } catch (Exception e) {
                    log.warn("Workspace selection failed (may not exist): {}", e.getMessage());
                }

                // 2. Terraform destroy 실행 (부분 생성된 리소스 제거)
                try {
                    context.updateStatus(InfraStatus.DESTROYING, 30, "Auto-rollback: Running terraform destroy...");
                    runCommand(workDir, "terraform", "destroy", "-auto-approve", "-input=false");
                    log.info("✓ Resources destroyed");
                } catch (Exception e) {
                    log.error("❌ Terraform destroy failed during rollback: {}", e.getMessage());
                    context.updateStatus(InfraStatus.FAILED, 0,
                        "Auto-rollback failed: " + e.getMessage() + " (Manual cleanup may be required)");
                    return;
                }

                // 3. Workspace 정리
                try {
                    context.updateStatus(InfraStatus.DESTROYING, 80, "Auto-rollback: Cleaning up workspace...");
                    runCommand(workDir, "terraform", "workspace", "select", "default");
                    runCommand(workDir, "terraform", "workspace", "delete", sessionId);
                    log.info("✓ Workspace deleted: {}", sessionId);
                } catch (Exception e) {
                    log.warn("Workspace cleanup failed: {}", e.getMessage());
                }

                // 4. 로컬 파일 삭제
                deleteDirectory(new File(workDir));

                // 5. 세션 제거
                sessions.remove(sessionId);

                context.updateStatus(InfraStatus.FAILED, 100,
                    "Provisioning failed. All resources have been rolled back successfully.");
                log.info("✅ Auto-rollback completed for session: {}", sessionId);

            } catch (Exception e) {
                log.error("❌ Critical error during auto-rollback for session: {}",
                    context.getSessionId(), e);
                context.updateStatus(InfraStatus.FAILED, 0,
                    "Auto-rollback critical error: " + e.getMessage() + " (URGENT: Manual cleanup required!)");
            }
        });
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

        // 2. Terraform destroy 실행 (State Lock 에러 시 자동 재시도)
        context.updateStatus(InfraStatus.DESTROYING, 30, "Running terraform destroy...");
        try {
            runCommand(workDir, "terraform", "destroy", "-auto-approve", "-input=false");
        } catch (RuntimeException e) {
            // ⭐ State Lock 에러 감지
            if (e.getMessage() != null && e.getMessage().contains("Error acquiring the state lock")) {
                log.warn("🔒 State lock detected. Attempting to force unlock...");
                handleStateLockError(context, workDir, e);
                // 재시도
                context.updateStatus(InfraStatus.DESTROYING, 40, "Retrying terraform destroy...");
                runCommand(workDir, "terraform", "destroy", "-auto-approve", "-input=false");
            } else {
                throw e; // Lock 에러가 아니면 그대로 재발생
            }
        }

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
     * JAR 파일 내부의 classpath 리소스를 지원합니다
     */
    private void copyTerraformFiles(String targetDir) throws IOException {
        Path targetPath = Paths.get(targetDir);

        // JAR 파일에서 실행 중인지 확인
        boolean runningFromJar = getClass().getResource("/" + terraformBasePath.replace("src/main/resources/", "")) == null;

        if (runningFromJar || !Paths.get(terraformBasePath).toFile().exists()) {
            // JAR에서 실행 중이거나 상대 경로로 접근 불가능한 경우, classpath에서 복사
            log.info("Copying Terraform files from classpath (running from JAR)...");
            copyFromClasspath(targetPath);
        } else {
            // 개발 환경에서는 파일 시스템에서 직접 복사
            log.info("Copying Terraform files from filesystem (development mode)...");
            copyFromFilesystem(targetPath);
        }

        log.info("✅ Terraform files copied to: {}", targetDir);
    }

    /**
     * classpath에서 Terraform 파일 복사 (JAR 실행 시)
     */
    private void copyFromClasspath(Path targetPath) throws IOException {
        String[] terraformFiles = {
            "terraform/backend.tf",
            "terraform/cloudwatch.tf",
            "terraform/dynamodb.tf",
            "terraform/ec2.tf",
            "terraform/iam.tf",
            "terraform/lambda.tf",
            "terraform/main.tf",
            "terraform/outputs.tf",
            "terraform/provider.tf",
            "terraform/s3.tf",
            "terraform/sns.tf",
            "terraform/variables.tf",
            "terraform/vpc.tf",
            "terraform/lambda/alarm_processor.py",
            "terraform/scripts/bootstrap-backend.sh"
        };

        for (String resourcePath : terraformFiles) {
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (inputStream == null) {
                    log.warn("Resource not found: {}", resourcePath);
                    continue;
                }

                // 파일 경로에서 'terraform/' 제거하고 대상 경로 생성
                String relativePath = resourcePath.replace("terraform/", "");
                Path destFile = targetPath.resolve(relativePath);
                Files.createDirectories(destFile.getParent());
                Files.copy(inputStream, destFile, StandardCopyOption.REPLACE_EXISTING);

                log.debug("Copied: {} -> {}", resourcePath, destFile);
            }
        }
    }

    /**
     * 파일 시스템에서 Terraform 파일 복사 (개발 환경)
     */
    private void copyFromFilesystem(Path targetPath) throws IOException {
        Path sourcePath = Paths.get(terraformBasePath);

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
     * ⭐ 명령어 실행 (로그 출력 없음 - graph 생성용)
     */
    private String runCommandSilent(String workDir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed with exit code " + exitCode + ": " + output);
        }

        return output.toString();
    }

    /**
     * ⭐ State Lock 에러 공통 처리
     */
    private void handleStateLockError(SessionContext context, String workDir, RuntimeException e) {
        String lockId = extractLockId(e.getMessage());
        if (lockId != null) {
            try {
                log.info("Forcing unlock with Lock ID: {}", lockId);
                context.updateStatus(context.getStatus(),
                    context.getProgressPercentage(),
                    "Detected state lock. Forcing unlock...");

                runCommand(workDir, "terraform", "force-unlock", "-force", lockId);
                log.info("✅ Lock released successfully");

                context.updateStatus(context.getStatus(),
                    context.getProgressPercentage(),
                    "Lock released. Retrying...");
            } catch (Exception unlockError) {
                log.error("Failed to force unlock: {}", unlockError.getMessage());
                throw e; // 원래 에러 재발생
            }
        } else {
            log.error("Could not extract Lock ID from error message");
            throw e;
        }
    }

    /**
     * ⭐ Terraform 에러 메시지에서 Lock ID 추출
     */
    private String extractLockId(String errorMessage) {
        try {
            // "ID:        198e8aeb-21d9-5621-bc64-e70cecc4dffb" 패턴 찾기
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("ID:\\s+([a-f0-9-]+)");
            java.util.regex.Matcher matcher = pattern.matcher(errorMessage);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.error("Failed to extract Lock ID", e);
        }
        return null;
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
     * ⭐ 세션 컨텍스트 조회 (프론트엔드 API용)
     */
    public SessionContext getSessionContext(String sessionId) {
        return sessions.get(sessionId);
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