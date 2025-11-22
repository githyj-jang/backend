package com.softbank.back.infra.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 사용자가 인프라 생성 시 조정 가능한 모든 파라미터를 담는 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TerraformRequest {

    // 필수 필드
    private String sessionId; // 사용자 세션 ID (프론트에서 전달)

    // AWS 기본 설정
    private String awsRegion = "ap-northeast-2";
    private String projectName = "penguin-land";
    private String environment = "dev";

    // EC2 설정
    private String ec2InstanceType = "t2.micro";
    private String ec2KeyName = "";

    // 알람 설정
    private String alertEmail = "";

    // CPU 임계값
    private Integer cpuWarningThreshold = 50;
    private Integer cpuCriticalThreshold = 70;

    // 에러율 임계값
    private Integer errorRateWarningThreshold = 3;
    private Integer errorRateCriticalThreshold = 5;

    // 레이턴시 임계값
    private Integer latencyWarningThreshold = 400;
    private Integer latencyCriticalThreshold = 700;
}

