package com.softbank.back.infra.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * 각 세션별 Terraform 실행 컨텍스트를 관리하는 클래스
 * ⭐ 서버 재시작 시 복구를 위해 파일로 저장
 */
@Data
@Slf4j
public class SessionContext {
    private String sessionId;
    private String workingDirectory;
    private InfraStatus status;
    private int progressPercentage;
    private String latestLog;
    private LocalDateTime lastUpdated;

    @JsonIgnore  // JSON 직렬화 제외
    private CompletableFuture<Void> currentTask;

    @JsonIgnore  // JSON 직렬화 제외 (너무 큼)
    private TerraformRequest request;

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public SessionContext() {
        // Jackson deserialization을 위한 기본 생성자
    }

    public SessionContext(String sessionId, String workingDirectory) {
        this.sessionId = sessionId;
        this.workingDirectory = workingDirectory;
        this.status = InfraStatus.INIT;
        this.progressPercentage = 0;
        this.latestLog = "Initialized";
        this.lastUpdated = LocalDateTime.now();
    }

    public void updateStatus(InfraStatus status, int progress, String log) {
        this.status = status;
        this.progressPercentage = progress;
        this.latestLog = log;
        this.lastUpdated = LocalDateTime.now();

        // ⭐ 상태 변경 시마다 파일로 저장
        saveToFile();
    }

    /**
     * ⭐ 현재 상태를 파일로 저장 (서버 다운 대비)
     */
    public void saveToFile() {
        try {
            Path progressFile = Paths.get(workingDirectory, ".progress.json");
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(this);
            Files.writeString(progressFile, json);
            log.debug("Progress saved for session: {}", sessionId);
        } catch (IOException e) {
            log.error("Failed to save progress for session: {}", sessionId, e);
        }
    }

    /**
     * ⭐ 파일에서 상태 복구
     */
    public static SessionContext loadFromFile(Path progressFile) throws IOException {
        String json = Files.readString(progressFile);
        return objectMapper.readValue(json, SessionContext.class);
    }
}

