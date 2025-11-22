package com.softbank.back.infra.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import jakarta.annotation.PostConstruct;

/**
 * Terraform Backend 리소스 관리 서비스
 * S3 버킷과 DynamoDB 테이블을 자동으로 확인하고 생성합니다.
 */
@Slf4j
@Service
public class TerraformBackendService {

    @Value("${terraform.backend.s3.bucket:penguin-land-shared-tfstate}")
    private String backendBucketName;

    @Value("${terraform.backend.dynamodb.table:penguin-land-shared-tflock}")
    private String lockTableName;

    @Value("${aws.region:ap-northeast-2}")
    private String awsRegion;

    private volatile boolean backendInitialized = false;
    private final Object initLock = new Object();

    private S3Client s3Client;
    private DynamoDbClient dynamoDbClient;

    @PostConstruct
    public void init() {
        try {
            // ⭐ AWS 자격 증명 검증
            validateAwsCredentials();

            this.s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();

            this.dynamoDbClient = DynamoDbClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            // 애플리케이션 시작 시 Backend 리소스 확인
            log.info("Checking Terraform backend resources...");
            checkBackendResources();
        } catch (Exception e) {
            log.error("❌ Failed to initialize Terraform Backend Service", e);
            log.error("Please check:");
            log.error("  1. AWS credentials are configured (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)");
            log.error("  2. AWS region is valid: {}", awsRegion);
            throw new IllegalStateException("Backend service initialization failed", e);
        }
    }

    /**
     * ⭐ AWS 자격 증명 검증
     */
    private void validateAwsCredentials() {
        try {
            software.amazon.awssdk.services.sts.StsClient stsClient =
                    software.amazon.awssdk.services.sts.StsClient.builder()
                            .region(Region.of(awsRegion))
                            .build();

            software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse identity =
                    stsClient.getCallerIdentity();

            log.info("✅ AWS credentials validated successfully");
            log.info("   - Account: {}", identity.account());
            log.info("   - UserId: {}", identity.userId());
            log.info("   - Region: {}", awsRegion);

            stsClient.close();
        } catch (software.amazon.awssdk.core.exception.SdkClientException e) {
            log.error("❌ AWS credentials not found or invalid!");
            log.error("   Please set environment variables:");
            log.error("   - AWS_ACCESS_KEY_ID");
            log.error("   - AWS_SECRET_ACCESS_KEY");
            log.error("   - AWS_DEFAULT_REGION (or configure aws.region in application.properties)");
            throw new IllegalStateException("AWS credentials not configured", e);
        }
    }

    /**
     * Backend 리소스 존재 여부 확인 및 생성
     * 스레드 세이프한 싱글톤 초기화
     */
    public void ensureBackendResourcesExist() throws Exception {
        if (backendInitialized) {
            return; // 이미 초기화됨
        }

        synchronized (initLock) {
            if (backendInitialized) {
                return; // Double-checked locking
            }

            log.info("=== Initializing Terraform Backend Resources ===");

            // 1. S3 버킷 확인 및 생성
            ensureS3BucketExists();

            // 2. DynamoDB 테이블 확인 및 생성
            ensureDynamoTableExists();

            backendInitialized = true;
            log.info("=== Terraform Backend Initialization Complete ===");
        }
    }

    /**
     * Backend 리소스 존재 여부 확인 (생성하지 않음)
     */
    private void checkBackendResources() {
        boolean s3Exists = checkS3BucketExists();
        boolean dynamoExists = checkDynamoTableExists();

        if (s3Exists && dynamoExists) {
            log.info("✅ Terraform backend resources are ready");
            log.info("   - S3 Bucket: {}", backendBucketName);
            log.info("   - DynamoDB Table: {}", lockTableName);
            backendInitialized = true;
        } else {
            log.warn("⚠️  Terraform backend resources not found:");
            if (!s3Exists) {
                log.warn("   - S3 Bucket '{}' does not exist", backendBucketName);
            }
            if (!dynamoExists) {
                log.warn("   - DynamoDB Table '{}' does not exist", lockTableName);
            }
            log.warn("   → Resources will be created automatically on first provision request");
        }
    }

    /**
     * S3 버킷 확인 및 생성
     */
    private void ensureS3BucketExists() throws Exception {
        if (checkS3BucketExists()) {
            log.info("✅ S3 bucket already exists: {}", backendBucketName);
            return;
        }

        log.info("Creating S3 bucket: {}", backendBucketName);

        try {
            CreateBucketRequest.Builder requestBuilder = CreateBucketRequest.builder()
                    .bucket(backendBucketName);

            // ap-northeast-2 등 us-east-1이 아닌 리전은 LocationConstraint 필요
            if (!awsRegion.equals("us-east-1")) {
                requestBuilder.createBucketConfiguration(
                    CreateBucketConfiguration.builder()
                            .locationConstraint(BucketLocationConstraint.fromValue(awsRegion))
                            .build()
                );
            }

            s3Client.createBucket(requestBuilder.build());
            log.info("✓ S3 bucket created");

            // 버저닝 활성화
            s3Client.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(backendBucketName)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build())
                    .build());
            log.info("✓ Bucket versioning enabled");

            // 암호화 설정
            s3Client.putBucketEncryption(PutBucketEncryptionRequest.builder()
                    .bucket(backendBucketName)
                    .serverSideEncryptionConfiguration(ServerSideEncryptionConfiguration.builder()
                            .rules(ServerSideEncryptionRule.builder()
                                    .applyServerSideEncryptionByDefault(
                                            ServerSideEncryptionByDefault.builder()
                                                    .sseAlgorithm(ServerSideEncryption.AES256)
                                                    .build()
                                    )
                                    .build())
                            .build())
                    .build());
            log.info("✓ Bucket encryption configured");

        } catch (BucketAlreadyOwnedByYouException e) {
            log.info("✓ Bucket already exists (owned by you)");
        } catch (S3Exception e) {
            log.error("Failed to create S3 bucket", e);
            throw new RuntimeException("Failed to create backend S3 bucket: " + e.getMessage(), e);
        }
    }

    /**
     * DynamoDB 테이블 확인 및 생성
     */
    private void ensureDynamoTableExists() throws Exception {
        if (checkDynamoTableExists()) {
            log.info("✅ DynamoDB table already exists: {}", lockTableName);
            return;
        }

        log.info("Creating DynamoDB lock table: {}", lockTableName);

        try {
            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(lockTableName)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("LockID")
                                    .attributeType(ScalarAttributeType.S)
                                    .build()
                    )
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("LockID")
                                    .keyType(KeyType.HASH)
                                    .build()
                    )
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build();

            dynamoDbClient.createTable(request);
            log.info("✓ DynamoDB table created");

            // 테이블 활성화 대기
            log.info("Waiting for table to become active...");
            try (DynamoDbWaiter waiter = DynamoDbWaiter.builder().client(dynamoDbClient).build()) {
                WaiterResponse<DescribeTableResponse> waiterResponse = waiter.waitUntilTableExists(
                        DescribeTableRequest.builder()
                                .tableName(lockTableName)
                                .build()
                );

                if (waiterResponse.matched().response().isPresent()) {
                    log.info("✓ Table is now active");
                }
            }

        } catch (ResourceInUseException e) {
            log.info("✓ Table already exists (created by another process)");
        } catch (DynamoDbException e) {
            log.error("Failed to create DynamoDB table", e);
            throw new RuntimeException("Failed to create lock table: " + e.getMessage(), e);
        }
    }

    /**
     * S3 버킷 존재 여부 확인
     */
    private boolean checkS3BucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder()
                    .bucket(backendBucketName)
                    .build());
            return true;
        } catch (NoSuchBucketException e) {
            return false;
        } catch (S3Exception e) {
            log.warn("Error checking S3 bucket existence: {}", e.getMessage());
            return false;
        }
    }

    /**
     * DynamoDB 테이블 존재 여부 확인
     */
    private boolean checkDynamoTableExists() {
        try {
            dynamoDbClient.describeTable(DescribeTableRequest.builder()
                    .tableName(lockTableName)
                    .build());
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        } catch (DynamoDbException e) {
            log.warn("Error checking DynamoDB table existence: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Backend 초기화 상태 확인
     */
    public boolean isBackendReady() {
        return backendInitialized || (checkS3BucketExists() && checkDynamoTableExists());
    }

    /**
     * Backend 리소스 정보 반환
     */
    public BackendInfo getBackendInfo() {
        return new BackendInfo(
                backendBucketName,
                lockTableName,
                awsRegion,
                checkS3BucketExists(),
                checkDynamoTableExists(),
                backendInitialized
        );
    }

    /**
     * Backend 정보 DTO
     */
    public record BackendInfo(
            String s3Bucket,
            String dynamoTable,
            String region,
            boolean s3Exists,
            boolean dynamoExists,
            boolean initialized
    ) {}
}

