# ===================================================================
# Terraform State Lock 테이블 (공유)
# ===================================================================
# S3 Backend 사용 시 동시 실행을 방지하기 위한 Lock 테이블입니다.
# 모든 workspace가 하나의 테이블을 공유합니다.
# 
# ⚠️ 이 테이블은 bootstrap-backend.sh로 생성되며,
#    Terraform으로 관리하지 않습니다. (Import 불필요)
# 
# 생성 방법:
#   ./scripts/bootstrap-backend.sh 실행 (최초 1회만)
# 
# 참고:
#   - 각 workspace마다 import 할 필요 없음
#   - 수동으로 생성된 테이블을 그대로 사용
# ===================================================================

# DynamoDB Lock 테이블은 Terraform으로 관리하지 않음
# bootstrap-backend.sh 스크립트로 생성된 테이블을 사용
#
# 테이블명: penguin-land-shared-tflock
# 용도: 모든 workspace의 State Lock 공유

# DynamoDB Table for Application Data
resource "aws_dynamodb_table" "app_data" {
  name         = "${var.project_name}-${var.session_id}-data"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"
  range_key    = "timestamp"

  attribute {
    name = "id"
    type = "S"
  }

  attribute {
    name = "timestamp"
    type = "N"
  }

  attribute {
    name = "status"
    type = "S"
  }

  global_secondary_index {
    name            = "StatusIndex"
    hash_key        = "status"
    range_key       = "timestamp"
    projection_type = "ALL"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = true
  }

  tags = {
    Name    = "${var.project_name}-${var.session_id}-data"
    Purpose = "Application data storage"
  }
}

# DynamoDB Table for Monitoring Metrics
resource "aws_dynamodb_table" "metrics" {
  name         = "${var.project_name}-${var.session_id}-metrics"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "metric_name"
  range_key    = "timestamp"

  attribute {
    name = "metric_name"
    type = "S"
  }

  attribute {
    name = "timestamp"
    type = "N"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  tags = {
    Name    = "${var.project_name}-${var.session_id}-metrics"
    Purpose = "Metrics data storage"
  }
}
