# 🐧 Penguin Land - Backend

**AWS 멀티테넌트 인프라 자동 프로비저닝 플랫폼**

> Softbank Cloud Infrastructure Hackathon 2025

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.8-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Terraform](https://img.shields.io/badge/Terraform-1.6+-purple.svg)](https://www.terraform.io/)
[![AWS](https://img.shields.io/badge/AWS-10%2B%20Services-yellow.svg)](https://aws.amazon.com/)

---

## 🧑‍💻 My Contribution

이 프로젝트에서 저는 **Terraform 기반 인프라 자동화 시스템 전체**를 설계하고 구현했습니다.

### 담당 영역 요약

| 영역 | 설명 |
|------|------|
| **Terraform Service Layer** | 비동기 인프라 프로비저닝 엔진 (1,076줄) |
| **Backend Service** | S3/DynamoDB 기반 Remote State 관리 (324줄) |
| **REST API** | 배포/삭제/상태 조회 API 2개 컨트롤러 |
| **Terraform IaC** | 13개 HCL 파일 (1,540줄), 15개 AWS 리소스 |
| **동시성 제어** | Semaphore + ThreadPool 기반 실행 제한 |
| **세션 복구** | 서버 재시작 시 자동 복구 시스템 |

---

### 1. TerraformService.java (핵심 엔진)

> 📍 `src/main/java/com/softbank/back/infra/service/TerraformService.java`

**비동기 인프라 프로비저닝 서비스**로, 다음 기능들을 구현했습니다:

#### 주요 기능

| 기능 | 구현 내용 |
|------|----------|
| **비동기 처리** | `CompletableFuture`를 활용한 논블로킹 terraform apply/destroy |
| **동시 실행 제어** | `Semaphore(1, true)`로 최대 1개 작업만 동시 실행 |
| **작업 대기열** | `LinkedBlockingQueue(10)`로 최대 10개 작업 대기 |
| **타임아웃** | 10분 초과 시 자동 롤백 (`orTimeout`) |
| **State Lock 해제** | DynamoDB Lock 충돌 감지 → `force-unlock` 자동 실행 |
| **세션 복구** | `@PostConstruct`에서 `.progress.json` 기반 복구 |
| **Graceful Shutdown** | `@PreDestroy`에서 세션 저장 및 안전 종료 |
| **자동 롤백** | 실패 시 `terraform destroy`로 부분 리소스 정리 |

#### 코드 예시: 동시 실행 제어

```java
CompletableFuture<InfraResponse> task = CompletableFuture.supplyAsync(() -> {
    boolean acquired = executionSemaphore.tryAcquire(30, TimeUnit.SECONDS);
    if (!acquired) throw new RuntimeException("Server is too busy");
    try {
        return executeTerraformApply(context);
    } finally {
        executionSemaphore.release();
    }
}, terraformExecutor)
.orTimeout(10, TimeUnit.MINUTES)
.exceptionally(ex -> {
    executeAutoRollback(context);
    throw new RuntimeException("Provisioning failed", ex);
});
```

---

### 2. TerraformBackendService.java

> 📍 `src/main/java/com/softbank/back/infra/service/TerraformBackendService.java`

**AWS S3/DynamoDB 기반 Terraform 원격 백엔드 관리**:

| 기능 | 구현 내용 |
|------|----------|
| **AWS 자격 증명 검증** | STS `GetCallerIdentity` API로 유효성 확인 |
| **S3 Backend 자동 생성** | State 저장용 버킷 (버전 관리 + AES-256 암호화) |
| **DynamoDB Lock 테이블** | State Lock용 테이블 자동 생성 |
| **Double-checked Locking** | 멀티스레드 환경에서 안전한 초기화 |

```java
public void ensureBackendResourcesExist() {
    synchronized (initLock) {
        if (backendInitialized) return;
        ensureS3BucketExists();      // S3 버킷 + 버전 관리 + 암호화
        ensureDynamoTableExists();   // DynamoDB Lock 테이블
        backendInitialized = true;
    }
}
```

---

### 3. REST API Controllers

#### DeployController.java - 간소화 배포 API

| Endpoint | Method | 설명 |
|----------|--------|------|
| `/deploy` | POST | 기본 설정으로 인프라 배포 시작 |
| `/deploy/status/{sessionId}` | GET | 배포 진행 상태 조회 (0-100%) |
| `/deploy/resources/{sessionId}` | GET | 생성된 AWS 리소스 정보 조회 |
| `/deploy/{sessionId}` | DELETE | 인프라 삭제 (동기, 10분 타임아웃) |

#### InfraController.java - 상세 관리 API

| Endpoint | Method | 설명 |
|----------|--------|------|
| `/api/v1/infra/provision` | PUT | 커스텀 파라미터로 인프라 프로비저닝 |
| `/api/v1/infra/status/{sessionId}` | GET | 실시간 프로비저닝 상태 조회 |
| `/api/v1/infra/info/{sessionId}` | GET | Terraform outputs 조회 |
| `/api/v1/infra/destroy/{sessionId}` | DELETE | 인프라 삭제 (비동기) |
| `/api/v1/infra/sessions` | GET | 전체 세션 목록 조회 |
| `/api/v1/infra/server/resources` | GET | 서버 리소스 상태 (슬롯, 큐) |
| `/api/v1/infra/backend/status` | GET | Terraform Backend 상태 확인 |
| `/api/v1/infra/backend/initialize` | POST | Backend 수동 초기화 |

---

### 4. Data Models

| 클래스 | 설명 |
|--------|------|
| `SessionContext.java` | 세션별 실행 컨텍스트, 파일 기반 상태 저장/복구 |
| `TerraformRequest.java` | Terraform 변수 요청 DTO |
| `InfraResponse.java` | 인프라 정보 응답 DTO |
| `InfraStatus.java` | 상태 Enum (INIT → PLANNING → APPLYING → COMPLETE) |
| `ProvisioningLog.java` | 프로비저닝 로그 DTO |
| `DeployStatusResponse.java` | 배포 상태 응답 DTO |
| `DeployResourcesResponse.java` | 리소스 정보 응답 DTO |

---

### 5. Terraform Configuration (13개 파일, 1,540줄)

```
src/main/resources/terraform/
├── backend.tf        # S3 원격 백엔드 + DynamoDB Lock 설정
├── provider.tf       # AWS Provider 설정
├── variables.tf      # 변수 정의 (15개)
├── main.tf           # 메인 설정
├── vpc.tf            # VPC, Subnet, Internet Gateway, Route Table
├── ec2.tf            # EC2 인스턴스, Security Group, Elastic IP
├── s3.tf             # S3 버킷 (정적 파일)
├── dynamodb.tf       # DynamoDB 테이블 (3개)
├── lambda.tf         # Lambda 함수 (Python 3.11)
├── iam.tf            # IAM 역할 및 정책
├── cloudwatch.tf     # CloudWatch 알람, 로그, 메트릭, 대시보드
├── sns.tf            # SNS 토픽
├── outputs.tf        # Output 변수 (40개+)
└── lambda/
    └── alarm_processor.py  # Lambda 함수 코드
```

#### 생성되는 AWS 리소스 (사용자당)

| 카테고리 | 리소스 |
|----------|--------|
| **Network** | VPC, Public Subnet, Internet Gateway, Route Table, Security Group |
| **Compute** | EC2 (t2.micro), Elastic IP |
| **Storage** | S3 Bucket |
| **Database** | DynamoDB Tables (3개) |
| **Serverless** | Lambda Function |
| **Monitoring** | CloudWatch Alarms, Logs, Metrics, Dashboard |
| **Notification** | SNS Topics |
| **Security** | IAM Roles & Policies |

---

### 6. 주요 기술적 성과

| 항목 | Before | After | 개선율 |
|------|--------|-------|--------|
| Lambda 삭제 시간 | 40분 | 10초 | **99.5% ↓** |
| 전체 인프라 삭제 | 45분 | 4분 | **91% ↓** |
| 인프라 생성 시간 | 145초 | 65초 | **55% ↓** |
| NAT Gateway 비용 | $32/월 | $0 | **$32 절감** |
| API 응답 시간 | - | <100ms | 즉시 응답 |

---

### 7. 파일 구조

```
src/main/java/com/softbank/back/infra/
├── controller/
│   ├── DeployController.java        # 간소화 배포 API
│   └── InfraController.java         # 상세 인프라 API
├── service/
│   ├── TerraformService.java        # 핵심 Terraform 실행 엔진 (1,076줄)
│   └── TerraformBackendService.java # Backend 리소스 관리 (324줄)
└── model/
    ├── SessionContext.java          # 세션 컨텍스트 + 파일 복구
    ├── TerraformRequest.java        # 요청 DTO
    ├── InfraResponse.java           # 응답 DTO
    ├── InfraStatus.java             # 상태 Enum
    ├── ProvisioningLog.java         # 로그 DTO
    ├── DeployRequest.java           # 배포 요청 DTO
    ├── DeployStatusResponse.java    # 배포 상태 응답
    ├── DeployResourcesResponse.java # 리소스 응답
    └── ResourceInfo.java            # 리소스 정보
```

---

### 8. 사용 기술

| 분류 | 기술 |
|------|------|
| **Backend** | Spring Boot 3.5.8, Java 21 |
| **IaC** | Terraform 1.6+ |
| **AWS SDK** | AWS SDK for Java v2 (S3, DynamoDB, STS) |
| **비동기 처리** | CompletableFuture, ThreadPoolExecutor |
| **동시성 제어** | Semaphore, LinkedBlockingQueue |
| **상태 관리** | Terraform S3 Backend + DynamoDB Lock |
| **직렬화** | Jackson (JSON) |

---

## 🎯 프로젝트 소개

**Penguin Land**는 복잡한 AWS 인프라를 단 한 번의 API 호출로 자동 구축하는 클라우드 자동화 플랫폼입니다.

### 핵심 가치

- **⚡ 원클릭 프로비저닝**: VPC부터 CloudWatch까지 15개 리소스를 1분 만에 자동 생성
- **🔄 자동 복구**: 서버 재시작 시 모든 세션 자동 복원
- **🔒 State Lock 자동 해제**: Terraform State Lock 충돌 자동 해결
- **💰 비용 최적화**: NAT Gateway 제거로 $32/월 절감 (사용자당)
- **🚀 비동기 처리**: 즉시 응답 (<100ms) 후 백그라운드 실행
- **👥 멀티테넌시**: Terraform Workspace로 사용자별 완전 격리

---

## 🏗️ 시스템 아키텍처

```
Frontend → Spring Boot Backend → Terraform → AWS (10+ Services)
              ↓
         • 비동기 처리 (CompletableFuture)
         • 세션 복구 (@PostConstruct)
         • State Lock 자동 해제
         • 자동 롤백
         • Semaphore 동시 실행 제어
```

---

## 🚀 빠른 시작

### 사전 요구사항

- Java 21+
- Terraform 1.6+
- AWS CLI (자격 증명 설정 완료)
- Gradle 8.0+

### 설치 및 실행

```bash
# 1. 프로젝트 클론
git clone <repository-url>
cd backend

# 2. 빌드
./gradlew clean build

# 3. 실행
./gradlew bootRun

# 또는 JAR 실행
java -jar build/libs/back-0.0.1-SNAPSHOT.jar
```

### 설정

`application.properties` 주요 설정:

```properties
# AWS 자격 증명
aws.region=ap-northeast-2

# Terraform 설정
terraform.base.path=src/main/resources/terraform
terraform.workspace.path=./terraform-workspaces
terraform.max.concurrent.operations=1
terraform.max.queue.size=10

# 서버 설정
server.port=8080
```

---

## 🔌 API 사용법

### 1. 인프라 프로비저닝

```bash
curl -X POST http://localhost:8080/deploy \
  -H "Content-Type: application/json" \
  -d '{ "sessionId": "user-001" }'

# 응답 (즉시, <100ms)
{
  "sessionId": "user-001"
}
```

### 2. 진행률 조회

```bash
curl http://localhost:8080/deploy/status/user-001

# 응답
{
  "sessionId": "user-001",
  "state": "APPLYING",
  "progress": 65,
  "currentStage": "Creating aws_instance.app_server...",
  "logs": [...]
}
```

### 3. 리소스 조회

```bash
curl http://localhost:8080/deploy/resources/user-001

# 응답
{
  "resources": {
    "ec2InstanceId": "i-0123456789abcdef",
    "ec2PublicIp": "54.180.1.2",
    "vpcId": "vpc-xxx",
    "s3BucketName": "penguin-land-user-001-static",
    "lambdaFunctionName": "penguin-land-user-001-alarm-processor"
  }
}
```

### 4. 인프라 삭제

```bash
curl -X DELETE http://localhost:8080/deploy/user-001

# 응답
{
  "message": "Resources deleted successfully",
  "sessionId": "user-001"
}
```

---

## 🔧 핵심 기술

### 비동기 프로비저닝

```java
CompletableFuture<InfraResponse> task = CompletableFuture
    .supplyAsync(() -> executeTerraformApply(context), terraformExecutor)
    .orTimeout(10, TimeUnit.MINUTES)
    .exceptionally(ex -> {
        executeAutoRollback(context);
        throw new RuntimeException("Provisioning failed", ex);
    });
```

### 서버 재시작 자동 복구

```java
@PostConstruct
public void recoverSessions() {
    // 로컬 파일(.progress.json)에서 세션 복구
    // S3 Backend에서 State 복구
}
```

### State Lock 자동 해제

```java
// 에러 메시지에서 Lock ID 추출
Pattern pattern = Pattern.compile("ID:\\s+([a-f0-9-]+)");
runCommand("terraform", "force-unlock", "-force", lockId);
```

---

## 🛠️ 기술 스택

### Backend
- **Framework**: Spring Boot 3.5.8
- **Language**: Java 21
- **Build Tool**: Gradle 8.0+
- **IaC Tool**: Terraform 1.6+

### AWS Services
- **Compute**: EC2, Lambda
- **Network**: VPC, Subnet, Internet Gateway
- **Storage**: S3, DynamoDB
- **Monitoring**: CloudWatch, SNS
- **Security**: IAM, Security Groups

---

## 📊 프로젝트 통계

| 항목 | 수치 |
|------|------|
| Java 코드 | ~3,000줄 |
| Terraform 코드 | 1,540줄 |
| AWS 서비스 | 10+ 개 |
| API 엔드포인트 | 12개 |
| 성능 개선 | 최대 99.5% |

---

## 👥 팀 Penguin

**Softbank Cloud Infrastructure Hackathon 2025**

- 개발 기간: 2025년 11월 14~23일
- 해커톤: 클라우드 인프라 개발자 대상 대회

---

## 🔗 관련 링크

- [AWS Documentation](https://docs.aws.amazon.com/)
- [Terraform Documentation](https://www.terraform.io/docs)
- [Spring Boot Documentation](https://spring.io/projects/spring-boot)

---

<div align="center">

**클라우드 인프라, 이제 펭귄처럼 쉽게! 🐧**

</div>
