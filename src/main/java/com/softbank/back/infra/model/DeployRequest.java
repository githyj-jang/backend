package com.softbank.back.infra.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Frontend-friendly simplified deployment request DTO
 * All settings use default values, only sessionId can be optionally specified
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeployRequest {
    /**
     * Session ID (optional)
     * If null, will be auto-generated using UUID
     */
    private String sessionId;
}

