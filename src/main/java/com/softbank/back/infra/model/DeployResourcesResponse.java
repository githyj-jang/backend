package com.softbank.back.infra.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 리소스 정보 조회 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeployResourcesResponse {
    /**
     * 배포된 리소스 정보
     */
    private ResourceInfo resources;

    /**
     * Terraform 그래프 (JSON)
     * 선택적 필드 - 현재는 구현하지 않음
     */
    private String graph;
}

