package com.softbank.back.infra.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 인프라 정보 응답 DTO (Terraform outputs 파싱 결과)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InfraResponse {
    private String sessionId;
    private InfraStatus status;
    private Map<String, Object> outputs;
    private String message;
}

