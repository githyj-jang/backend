package com.softbank.back.infra.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 프론트엔드용 배포 상태 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeployStatusResponse {
    /**
     * 세션 ID
     */
    private String sessionId;

    /**
     * 배포 상태
     * INIT, PLANNING, APPLYING, COMPLETE, FAILED, DESTROYING
     */
    private String state;

    /**
     * 진행률 (0-100)
     */
    private Integer progress;

    /**
     * 현재 단계 설명
     */
    private String currentStage;

    /**
     * 로그 배열 (시간 순)
     */
    private List<String> logs;

    /**
     * 생성 시간 (ISO 8601)
     */
    private String createdAt;

    /**
     * 마지막 업데이트 시간 (ISO 8601)
     */
    private String updatedAt;
}

