package com.softbank.back.infra.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 배포된 AWS 리소스 정보
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceInfo {
    /**
     * EC2 인스턴스 ID
     */
    private String ec2InstanceId;

    /**
     * EC2 Public IP 주소
     */
    private String ec2PublicIp;

    /**
     * VPC ID
     */
    private String vpcId;

    /**
     * DynamoDB 테이블 이름
     */
    private String dynamoDbTableName;

    /**
     * S3 버킷 이름
     */
    private String s3BucketName;

    /**
     * Lambda 함수 이름
     */
    private String lambdaFunctionName;

    /**
     * SNS Topic ARN
     */
    private String snsTopicArn;
}

