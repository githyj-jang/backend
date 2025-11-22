# ===================================================================
# Terraform Backend Configuration (S3 공유 버킷 방식)
# ===================================================================
# 
# 팀 협업을 위해 S3에 State를 저장합니다.
# 모든 사용자(workspace)가 하나의 S3 버킷을 공유하며,
# workspace별로 자동으로 경로가 분리됩니다.
# 
# State 저장 위치:
#   s3://penguin-land-shared-tfstate/env:/user-001/terraform.tfstate
#   s3://penguin-land-shared-tfstate/env:/user-002/terraform.tfstate
#   s3://penguin-land-shared-tfstate/env:/default/terraform.tfstate
#
# 장점:
#   ✅ 팀원들과 State 공유 가능
#   ✅ 동시 실행 방지 (DynamoDB Lock)
#   ✅ State 버전 관리 (S3 Versioning)
#   ✅ 암호화된 안전한 저장
#   ✅ EC2 재시작/삭제 시에도 State 유지
#
# 사용 방법:
#   1. ./scripts/bootstrap-backend.sh 실행 (최초 1회)
#   2. terraform init 실행
#   3. terraform workspace new user-001
#   4. terraform apply
# ===================================================================

terraform {
  backend "s3" {
    bucket         = "penguin-land-shared-tfstate" # 모든 workspace가 공유
    key            = "terraform.tfstate"           # workspace별 자동 분리
    region         = "ap-northeast-2"              # 한국-서울 리전
    dynamodb_table = "penguin-land-shared-tflock"  # State Lock 테이블
    encrypt        = true                          # 암호화 활성화

    # Workspace별 State 파일 경로 자동 분리
    # 예: env:/user-001/terraform.tfstate
    workspace_key_prefix = "env:"
  }
}
