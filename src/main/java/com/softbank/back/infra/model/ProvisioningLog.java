package com.softbank.back.infra.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * FR-02: Terraform 배포 상태 및 로그 정보
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProvisioningLog {
    private String sessionId;
    private InfraStatus status;
    private int progressPercentage;
    private String latestLog;
    private LocalDateTime updateTime;

    public ProvisioningLog(InfraStatus status, int progressPercentage, String latestLog, LocalDateTime updateTime) {
        this.status = status;
        this.progressPercentage = progressPercentage;
        this.latestLog = latestLog;
        this.updateTime = updateTime;
    }
}