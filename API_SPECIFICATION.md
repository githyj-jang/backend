# Penguin Land Infrastructure Management API 명세서

## 📋 목차
1. [개요](#개요)
2. [Base URL](#base-url)
3. [인증](#인증)
4. [API 엔드포인트](#api-엔드포인트)
5. [데이터 모델](#데이터-모델)
6. [에러 처리](#에러-처리)
7. [사용 시나리오](#사용-시나리오)

---

## 개요

Penguin Land Infrastructure Management API는 AWS 인프라를 동적으로 프로비저닝하고 관리하는 RESTful API입니다. 
각 사용자는 고유한 `sessionId`로 구분되어 독립적인 인프라 환경을 생성하고 관리할 수 있습니다.

### 주요 기능
- ✅ AWS 인프라 자동 프로비저닝 (VPC, EC2, S3, DynamoDB, Lambda, CloudWatch)
- ✅ 실시간 프로비저닝 진행 상황 조회
- ✅ 생성된 인프라 리소스 정보 조회
- ✅ 인프라 삭제
- ✅ 다중 사용자 세션 관리 (Terraform Workspace 기반)

### 기술 스택
- **Backend**: Spring Boot 3.x + Java 17
- **Infrastructure as Code**: Terraform (AWS Provider)
- **State Management**: AWS S3 + DynamoDB (Locking)
- **Monitoring**: AWS CloudWatch + Lambda

---

## Base URL

```
http://localhost:8080/api/v1/infra
```

프로덕션 환경에서는 실제 도메인으로 변경됩니다.

---

## 인증

현재 버전에서는 별도의 인증이 필요하지 않습니다.
`sessionId`를 통해 각 사용자의 리소스를 격리합니다.

> **중요**: 프로덕션 배포 시 JWT 또는 OAuth2 인증 추가 권장

---

## API 엔드포인트

### 1. 인프라 생성/업데이트

#### `PUT /api/v1/infra/provision`

사용자가 조정 가능한 파라미터를 기반으로 AWS 인프라를 생성하거나 업데이트합니다.

**요청 방식**: `PUT` (멱등성 보장)

**Request Body**:
```json
{
  "sessionId": "user-001",
  "awsRegion": "ap-northeast-2",
  "projectName": "penguin-land",
  "environment": "dev",
  "ec2InstanceType": "t2.micro",
  "ec2KeyName": "my-keypair",
  "alertEmail": "admin@example.com",
  "cpuWarningThreshold": 50,
  "cpuCriticalThreshold": 70,
  "errorRateWarningThreshold": 3,
  "errorRateCriticalThreshold": 5,
  "latencyWarningThreshold": 400,
  "latencyCriticalThreshold": 700
}
```

**필수 필드**:
- `sessionId` (String): 사용자 세션 ID (고유값 권장)

**선택 필드** (기본값 적용):
- `awsRegion` (String): AWS 리전 (기본: "ap-northeast-2")
- `projectName` (String): 프로젝트 이름 (기본: "penguin-land")
- `environment` (String): 환경 (기본: "dev")
- `ec2InstanceType` (String): EC2 인스턴스 타입 (기본: "t2.micro")
- `ec2KeyName` (String): EC2 키페어 이름 (기본: "")
- `alertEmail` (String): 알람 수신 이메일 (기본: "")
- `cpuWarningThreshold` (Integer): CPU 경고 임계값 (기본: 50)
- `cpuCriticalThreshold` (Integer): CPU 위험 임계값 (기본: 70)
- `errorRateWarningThreshold` (Integer): 에러율 경고 임계값 (기본: 3)
- `errorRateCriticalThreshold` (Integer): 에러율 위험 임계값 (기본: 5)
- `latencyWarningThreshold` (Integer): 지연시간 경고 임계값 ms (기본: 400)
- `latencyCriticalThreshold` (Integer): 지연시간 위험 임계값 ms (기본: 700)

**Response (202 Accepted)**:
```json
{
  "sessionId": "user-001",
  "status": "ACCEPTED",
  "message": "Infrastructure provisioning started. Use /status/{sessionId} to check progress."
}
```

**에러 응답 (400 Bad Request)**:
```json
{
  "error": "Session ID is required",
  "message": "Please provide a valid session ID"
}
```

**에러 응답 (409 Conflict)**:
```json
{
  "error": "CONFLICT",
  "message": "Provisioning already in progress for session: user-001"
}
```

#### 🔍 상세 로직 구조

1. **세션 컨텍스트 관리**
   - `sessionId`로 `SessionContext` 조회 또는 생성
   - 각 세션은 독립적인 작업 디렉토리 보유: `./terraform-workspaces/{sessionId}/`
   - 중복 실행 방지: 기존 작업이 진행 중이면 `409 Conflict` 반환

2. **비동기 프로비저닝 시작**
   - `CompletableFuture`를 사용한 비동기 처리
   - 즉시 `202 Accepted` 응답 반환
   - 백그라운드에서 Terraform 실행

3. **Terraform 실행 과정** (백그라운드)
   
   a. **Backend 리소스 확인 및 생성** (5%)
   - S3 버킷: `penguin-land-shared-tfstate` (Terraform state 저장)
   - DynamoDB 테이블: `penguin-land-shared-tflock` (동시 실행 방지 Lock)
   - 존재하지 않으면 자동 생성

   b. **Terraform 파일 복사** (10%)
   - `src/main/resources/terraform/` → `./terraform-workspaces/{sessionId}/`
   - 복사 대상: `*.tf`, `*.py`, `*.sh` 파일
   - 필수 파일 검증: `provider.tf`, `variables.tf`, `backend.tf`, `vpc.tf`, `ec2.tf`, `lambda/alarm_processor.py`

   c. **terraform.tfvars 생성** (15%)
   - 사용자 요청 파라미터를 `terraform.tfvars` 파일로 변환
   ```hcl
   session_id = "user-001"
   aws_region = "ap-northeast-2"
   project_name = "penguin-land"
   environment = "dev"
   ec2_instance_type = "t2.micro"
   # ... 나머지 변수
   ```

   d. **terraform init** (20%)
   - Terraform 초기화
   - Backend 연결 (S3 + DynamoDB)
   - Provider 플러그인 다운로드
   ```bash
   terraform init -input=false
   ```

   e. **Workspace 설정** (25%)
   - Terraform workspace 생성 또는 선택
   - workspace 이름 = `sessionId`
   - workspace별로 state가 S3에 분리 저장됨
   ```bash
   terraform workspace new user-001  # 또는
   terraform workspace select user-001
   ```

   f. **terraform plan** (40%)
   - 실행 계획 생성
   - 생성/변경/삭제될 리소스 확인
   ```bash
   terraform plan -out=tfplan -input=false
   ```

   g. **terraform apply** (60% ~ 100%)
   - 인프라 프로비저닝 실행
   - 생성 리소스:
     - VPC, Subnet, Internet Gateway, Route Table
     - Security Group
     - EC2 인스턴스 + Elastic IP
     - S3 버킷 (정적 파일 저장용)
     - DynamoDB 테이블 2개 (앱 데이터, 메트릭)
     - Lambda 함수 (알람 처리)
     - SNS 토픽 (알람 전송)
     - CloudWatch 대시보드, 알람
   ```bash
   terraform apply -input=false -auto-approve tfplan
   ```

   h. **outputs 파싱** (100%)
   - Terraform outputs를 JSON으로 파싱
   - EC2 IP, S3 버킷명 등 리소스 정보 추출

4. **상태 저장**
   - 각 단계마다 `SessionContext` 업데이트
   - 진행률, 로그 메시지 기록
   - `.progress.json` 파일로 저장 (서버 재시작 대비)

5. **에러 처리**
   - 각 단계 실패 시 상태를 `FAILED`로 변경
   - 에러 메시지를 `latestLog`에 저장

---

### 2. 프로비저닝 상태 조회

#### `GET /api/v1/infra/status/{sessionId}`

실시간으로 프로비저닝 진행 상황을 확인합니다. (폴링 방식 권장)

**경로 파라미터**:
- `sessionId` (String): 조회할 세션 ID

**Response (200 OK)**:
```json
{
  "sessionId": "user-001",
  "status": "APPLYING",
  "progressPercentage": 75,
  "latestLog": "Running terraform apply...",
  "updateTime": "2025-11-22T14:35:20"
}
```

**상태 값** (`status`):
- `INIT`: 초기화 중
- `PLANNING`: Terraform plan 실행 중
- `APPLYING`: Terraform apply 실행 중 (인프라 생성)
- `COMPLETE`: 프로비저닝 완료
- `FAILED`: 실패
- `DESTROYING`: 인프라 삭제 중

**진행률** (`progressPercentage`):
- 0 ~ 100 사이의 정수
- 단계별 진행률:
  - 0-20%: 초기화
  - 20-40%: 계획 수립
  - 40-100%: 인프라 생성

#### 🔍 상세 로직 구조

1. **세션 컨텍스트 조회**
   - `sessions` Map에서 `sessionId`로 `SessionContext` 조회
   - 존재하지 않으면 기본 응답 반환 (status: INIT, 로그: "No session found")

2. **현재 상태 반환**
   - `SessionContext`의 현재 상태를 `ProvisioningLog` DTO로 변환
   - 실시간 진행 상황, 최신 로그, 마지막 업데이트 시간 포함

3. **폴링 권장 주기**
   - 2~5초 간격으로 폴링
   - `status`가 `COMPLETE` 또는 `FAILED`가 되면 폴링 중지

---

### 3. 인프라 정보 조회

#### `GET /api/v1/infra/info/{sessionId}`

프로비저닝 완료 후 생성된 리소스 정보를 조회합니다.

**경로 파라미터**:
- `sessionId` (String): 조회할 세션 ID

**Response (200 OK)**:
```json
{
  "sessionId": "user-001",
  "status": "COMPLETE",
  "outputs": {
    "vpc_id": "vpc-0123456789abcdef0",
    "vpc_cidr": "10.0.0.0/16",
    "public_subnet_id": "subnet-0123456789abcdef0",
    "private_subnet_id": "subnet-0fedcba9876543210",
    "ec2_instance_id": "i-0123456789abcdef0",
    "ec2_private_ip": "10.0.1.100",
    "ec2_public_ip": "54.180.123.45",
    "ec2_instance_state": "running",
    "static_files_bucket_name": "penguin-land-user-001-static-files",
    "static_files_bucket_arn": "arn:aws:s3:::penguin-land-user-001-static-files",
    "app_data_table_name": "penguin-land-user-001-app-data",
    "metrics_table_name": "penguin-land-user-001-metrics",
    "lambda_function_name": "penguin-land-user-001-alarm-processor",
    "lambda_function_arn": "arn:aws:lambda:ap-northeast-2:123456789012:function:penguin-land-user-001-alarm-processor",
    "sns_topic_arn": "arn:aws:sns:ap-northeast-2:123456789012:penguin-land-user-001-alarms",
    "sns_topic_name": "penguin-land-user-001-alarms",
    "cloudwatch_dashboard_name": "penguin-land-user-001-dashboard",
    "cloudwatch_log_group_name": "/aws/ec2/penguin-land-user-001"
  },
  "message": "Infrastructure information retrieved"
}
```

**에러 응답 (404 Not Found)**:
- 세션이 존재하지 않거나 인프라가 아직 생성되지 않은 경우

#### 🔍 상세 로직 구조

1. **세션 검증**
   - `sessionId`로 `SessionContext` 조회
   - 존재하지 않으면 `IllegalStateException` 발생 → 404 응답

2. **Terraform outputs 파싱**
   - 작업 디렉토리에서 `terraform output -json` 실행
   - JSON 파싱하여 `outputs` Map 생성
   ```bash
   terraform output -json
   ```

3. **응답 생성**
   - `InfraResponse` DTO 생성
   - `sessionId`, `status`, `outputs`, `message` 포함

4. **에러 처리**
   - Terraform outputs 파싱 실패 시 빈 Map 반환
   - 인프라가 완전히 생성되지 않았을 경우 일부 outputs만 반환될 수 있음

---

### 4. 인프라 삭제

#### `DELETE /api/v1/infra/destroy/{sessionId}`

특정 세션의 모든 인프라 리소스를 삭제합니다.

**경로 파라미터**:
- `sessionId` (String): 삭제할 세션 ID

**Response (202 Accepted)**:
```json
{
  "sessionId": "user-001",
  "status": "ACCEPTED",
  "message": "Infrastructure destruction started. Use /status/{sessionId} to check progress."
}
```

**에러 응답 (409 Conflict)**:
```json
{
  "error": "CONFLICT",
  "message": "Another operation is in progress for session: user-001"
}
```

#### 🔍 상세 로직 구조

1. **세션 검증**
   - `sessionId`로 `SessionContext` 조회
   - 존재하지 않으면 `IllegalStateException` 발생 → 409 응답

2. **중복 작업 확인**
   - 이미 실행 중인 작업이 있는지 확인
   - 진행 중이면 `IllegalStateException` 발생 → 409 응답

3. **비동기 삭제 시작**
   - `CompletableFuture`를 사용한 비동기 처리
   - 즉시 `202 Accepted` 응답 반환
   - 백그라운드에서 Terraform destroy 실행

4. **Terraform destroy 실행 과정** (백그라운드)
   
   a. **Workspace 선택** (10%)
   ```bash
   terraform workspace select user-001
   ```

   b. **terraform destroy 실행** (30% ~ 80%)
   - 모든 리소스 삭제 (역순으로 삭제)
   ```bash
   terraform destroy -auto-approve -input=false
   ```

   c. **Workspace 정리** (80% ~ 100%)
   - default workspace로 전환
   - 해당 workspace 삭제
   ```bash
   terraform workspace select default
   terraform workspace delete user-001
   ```

5. **세션 정리**
   - `sessions` Map에서 세션 제거
   - 작업 디렉토리 삭제

6. **상태 업데이트**
   - 각 단계마다 `status`를 `DESTROYING`으로 업데이트
   - 완료 시 `COMPLETE` 상태로 변경

---

### 5. 모든 세션 조회

#### `GET /api/v1/infra/sessions`

모든 활성 세션의 상태를 조회합니다. (관리용)

**Response (200 OK)**:
```json
[
  {
    "sessionId": "user-001",
    "status": "COMPLETE",
    "progressPercentage": 100,
    "latestLog": "Infrastructure provisioning completed!",
    "updateTime": "2025-11-22T14:40:00"
  },
  {
    "sessionId": "user-002",
    "status": "APPLYING",
    "progressPercentage": 65,
    "latestLog": "Running terraform apply...",
    "updateTime": "2025-11-22T14:42:15"
  }
]
```

#### 🔍 상세 로직 구조

1. **세션 목록 조회**
   - `sessions` Map의 모든 `SessionContext` 조회

2. **DTO 변환**
   - 각 `SessionContext`를 `ProvisioningLog`로 변환
   - List로 반환

---

### 6. Backend 상태 확인

#### `GET /api/v1/infra/backend/status`

Terraform Backend 리소스(S3, DynamoDB) 상태를 확인합니다.

**Response (200 OK)**:
```json
{
  "initialized": true,
  "s3Bucket": {
    "name": "penguin-land-shared-tfstate",
    "exists": true,
    "region": "ap-northeast-2"
  },
  "dynamoTable": {
    "name": "penguin-land-shared-tflock",
    "exists": true,
    "region": "ap-northeast-2"
  },
  "ready": true,
  "message": "Terraform backend is ready"
}
```

**Backend 준비 안됨 (200 OK)**:
```json
{
  "initialized": false,
  "s3Bucket": {
    "name": "penguin-land-shared-tfstate",
    "exists": false,
    "region": "ap-northeast-2"
  },
  "dynamoTable": {
    "name": "penguin-land-shared-tflock",
    "exists": false,
    "region": "ap-northeast-2"
  },
  "ready": false,
  "message": "Backend resources will be created automatically on first provision request"
}
```

#### 🔍 상세 로직 구조

1. **Backend 리소스 확인**
   - S3 버킷 존재 여부 확인: `s3:HeadBucket`
   - DynamoDB 테이블 존재 여부 확인: `dynamodb:DescribeTable`

2. **응답 생성**
   - 각 리소스의 이름, 존재 여부, 리전 정보 포함
   - `ready` 필드: 모든 리소스가 존재하면 `true`

---

### 7. Backend 수동 초기화

#### `POST /api/v1/infra/backend/initialize`

Terraform Backend 리소스를 수동으로 생성합니다. (관리자용)

**Response (200 OK)**:
```json
{
  "status": "SUCCESS",
  "message": "Backend resources initialized successfully",
  "s3Bucket": "penguin-land-shared-tfstate",
  "dynamoTable": "penguin-land-shared-tflock",
  "region": "ap-northeast-2"
}
```

**에러 응답 (500 Internal Server Error)**:
```json
{
  "status": "FAILED",
  "error": "Backend initialization failed",
  "message": "AccessDenied: You do not have permission to create S3 bucket"
}
```

#### 🔍 상세 로직 구조

1. **S3 버킷 생성**
   - 버킷명: `penguin-land-shared-tfstate`
   - 리전별 LocationConstraint 설정 (us-east-1 제외)
   - 버저닝 활성화 (state 이력 관리)
   - 암호화 활성화 (AES256)
   ```java
   s3Client.createBucket(CreateBucketRequest.builder()
       .bucket(backendBucketName)
       .createBucketConfiguration(...)
       .build());
   ```

2. **DynamoDB 테이블 생성**
   - 테이블명: `penguin-land-shared-tflock`
   - 파티션 키: `LockID` (String)
   - 결제 모드: PAY_PER_REQUEST (온디맨드)
   - 테이블 활성화 대기 (Waiter 사용)
   ```java
   dynamoDbClient.createTable(CreateTableRequest.builder()
       .tableName(lockTableName)
       .attributeDefinitions(...)
       .keySchema(...)
       .billingMode(BillingMode.PAY_PER_REQUEST)
       .build());
   ```

3. **초기화 플래그 설정**
   - `backendInitialized = true` 설정 (스레드 세이프)

4. **에러 처리**
   - AWS 권한 부족 시 에러 메시지 반환
   - 이미 존재하는 리소스는 무시 (BucketAlreadyOwnedByYouException, ResourceInUseException)

---

### 8. 헬스 체크

#### `GET /api/v1/infra/health`

서비스 상태를 확인합니다.

**Response (200 OK)**:
```json
{
  "status": "UP",
  "service": "Infrastructure Management Service"
}
```

---

## 데이터 모델

### TerraformRequest

인프라 생성 요청 DTO

```typescript
interface TerraformRequest {
  sessionId: string;              // 필수: 사용자 세션 ID
  awsRegion?: string;             // 기본값: "ap-northeast-2"
  projectName?: string;           // 기본값: "penguin-land"
  environment?: string;           // 기본값: "dev"
  ec2InstanceType?: string;       // 기본값: "t2.micro"
  ec2KeyName?: string;            // 기본값: ""
  alertEmail?: string;            // 기본값: ""
  cpuWarningThreshold?: number;   // 기본값: 50
  cpuCriticalThreshold?: number;  // 기본값: 70
  errorRateWarningThreshold?: number;    // 기본값: 3
  errorRateCriticalThreshold?: number;   // 기본값: 5
  latencyWarningThreshold?: number;      // 기본값: 400
  latencyCriticalThreshold?: number;     // 기본값: 700
}
```

### ProvisioningLog

프로비저닝 상태 및 로그 정보

```typescript
interface ProvisioningLog {
  sessionId: string;
  status: InfraStatus;
  progressPercentage: number;  // 0 ~ 100
  latestLog: string;
  updateTime: string;  // ISO 8601 format
}
```

### InfraResponse

인프라 정보 응답

```typescript
interface InfraResponse {
  sessionId: string;
  status: InfraStatus;
  outputs: {
    vpc_id?: string;
    vpc_cidr?: string;
    public_subnet_id?: string;
    private_subnet_id?: string;
    ec2_instance_id?: string;
    ec2_private_ip?: string;
    ec2_public_ip?: string;
    ec2_instance_state?: string;
    static_files_bucket_name?: string;
    static_files_bucket_arn?: string;
    app_data_table_name?: string;
    metrics_table_name?: string;
    lambda_function_name?: string;
    lambda_function_arn?: string;
    sns_topic_arn?: string;
    sns_topic_name?: string;
    cloudwatch_dashboard_name?: string;
    cloudwatch_log_group_name?: string;
  };
  message: string;
}
```

### InfraStatus (Enum)

```typescript
enum InfraStatus {
  INIT = "INIT",              // 초기화 중
  PLANNING = "PLANNING",      // 계획 수립 중
  APPLYING = "APPLYING",      // 인프라 생성 중
  COMPLETE = "COMPLETE",      // 완료
  FAILED = "FAILED",          // 실패
  DESTROYING = "DESTROYING"   // 삭제 중
}
```

---

## 에러 처리

### HTTP 상태 코드

- **200 OK**: 요청 성공 (조회)
- **202 Accepted**: 비동기 작업 시작됨
- **400 Bad Request**: 잘못된 요청 (필수 필드 누락 등)
- **404 Not Found**: 리소스를 찾을 수 없음
- **409 Conflict**: 충돌 (이미 진행 중인 작업 존재)
- **500 Internal Server Error**: 서버 내부 오류

### 에러 응답 형식

```typescript
interface ErrorResponse {
  error: string;    // 에러 코드 (CONFLICT, INTERNAL_ERROR 등)
  message: string;  // 상세 에러 메시지
}
```

---

## 사용 시나리오

### 시나리오 1: 새 인프라 생성

```javascript
// 1. 인프라 생성 요청
const response = await fetch('http://localhost:8080/api/v1/infra/provision', {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    sessionId: 'user-001',
    awsRegion: 'ap-northeast-2',
    ec2InstanceType: 't2.micro',
    alertEmail: 'admin@example.com'
  })
});

const result = await response.json();
console.log(result);
// { sessionId: "user-001", status: "ACCEPTED", message: "..." }

// 2. 진행 상황 폴링 (2초 간격)
const pollStatus = async () => {
  const statusResponse = await fetch('http://localhost:8080/api/v1/infra/status/user-001');
  const status = await statusResponse.json();
  
  console.log(`Progress: ${status.progressPercentage}% - ${status.latestLog}`);
  
  if (status.status === 'COMPLETE') {
    console.log('✅ Provisioning completed!');
    return true;
  } else if (status.status === 'FAILED') {
    console.log('❌ Provisioning failed:', status.latestLog);
    return false;
  }
  
  // 계속 폴링
  setTimeout(pollStatus, 2000);
};

pollStatus();

// 3. 인프라 정보 조회 (완료 후)
const infoResponse = await fetch('http://localhost:8080/api/v1/infra/info/user-001');
const info = await infoResponse.json();
console.log('EC2 Public IP:', info.outputs.ec2_public_ip);
console.log('S3 Bucket:', info.outputs.static_files_bucket_name);
```

### 시나리오 2: 인프라 삭제

```javascript
// 1. 인프라 삭제 요청
const response = await fetch('http://localhost:8080/api/v1/infra/destroy/user-001', {
  method: 'DELETE'
});

const result = await response.json();
console.log(result);
// { sessionId: "user-001", status: "ACCEPTED", message: "..." }

// 2. 진행 상황 폴링
const pollDestroy = async () => {
  const statusResponse = await fetch('http://localhost:8080/api/v1/infra/status/user-001');
  const status = await statusResponse.json();
  
  console.log(`Progress: ${status.progressPercentage}% - ${status.latestLog}`);
  
  if (status.status === 'COMPLETE') {
    console.log('✅ Infrastructure destroyed!');
    return true;
  }
  
  setTimeout(pollDestroy, 2000);
};

pollDestroy();
```

### 시나리오 3: 다중 사용자 관리

```javascript
// 관리자 대시보드에서 모든 세션 조회
const response = await fetch('http://localhost:8080/api/v1/infra/sessions');
const sessions = await response.json();

sessions.forEach(session => {
  console.log(`Session: ${session.sessionId}`);
  console.log(`  Status: ${session.status} (${session.progressPercentage}%)`);
  console.log(`  Log: ${session.latestLog}`);
  console.log(`  Updated: ${session.updateTime}`);
});
```

---

## 추가 정보

### Terraform Backend 구조

모든 사용자가 하나의 S3 버킷을 공유하며, workspace별로 state가 분리됩니다.

```
s3://penguin-land-shared-tfstate/
  └── env:/
      ├── user-001/
      │   └── terraform.tfstate
      ├── user-002/
      │   └── terraform.tfstate
      └── default/
          └── terraform.tfstate
```

### 생성되는 AWS 리소스

각 세션(`sessionId`)당 다음 리소스가 생성됩니다:

- **네트워크**: VPC, 퍼블릭 서브넷, 프라이빗 서브넷, Internet Gateway, Route Table, Security Group
- **컴퓨팅**: EC2 인스턴스 1개 + Elastic IP
- **스토리지**: S3 버킷 1개
- **데이터베이스**: DynamoDB 테이블 2개
- **서버리스**: Lambda 함수 1개
- **알림**: SNS 토픽 1개
- **모니터링**: CloudWatch 대시보드, 알람 (CPU, 에러율, 지연시간)

### 비용 예상 (ap-northeast-2 기준)

- EC2 t2.micro: 약 $0.0116/시간 (~$8.5/월)
- S3: 저장 용량 + 요청 수에 따라
- DynamoDB: PAY_PER_REQUEST 모드 (사용량에 따라)
- Lambda: 100만 요청/월까지 무료
- SNS: 100만 알림/월까지 무료
- CloudWatch: 기본 메트릭 무료, 커스텀 메트릭 유료

---

## 문의 및 지원

- **프로젝트**: Penguin Land
- **버전**: 1.0.0
- **마지막 업데이트**: 2025-11-22

---

## 변경 이력

### v1.0.0 (2025-11-22)
- 초기 API 명세서 작성
- 인프라 생성/조회/삭제 API 구현
- Terraform Backend 자동 관리 기능 추가
- 다중 사용자 세션 관리 (Workspace 기반)

