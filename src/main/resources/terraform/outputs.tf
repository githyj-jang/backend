# VPC Outputs
output "vpc_id" {
  description = "생성된 VPC의 ID"
  value       = aws_vpc.main.id
}

output "vpc_cidr" {
  description = "VPC의 CIDR 블록"
  value       = aws_vpc.main.cidr_block
}

# Subnet Outputs
output "public_subnet_id" {
  description = "퍼블릭 서브넷 ID"
  value       = aws_subnet.public.id
}

output "private_subnet_id" {
  description = "프라이빗 서브넷 ID"
  value       = aws_subnet.private.id
}

# NAT Gateway Outputs
output "nat_gateway_id" {
  description = "NAT Gateway ID"
  value       = aws_nat_gateway.main.id
}

output "nat_gateway_public_ip" {
  description = "NAT Gateway의 퍼블릭 IP"
  value       = aws_eip.nat.public_ip
}

# EC2 Outputs
output "ec2_instance_id" {
  description = "EC2 인스턴스 ID"
  value       = aws_instance.app_server.id
}

output "ec2_private_ip" {
  description = "EC2 인스턴스의 프라이빗 IP 주소"
  value       = aws_instance.app_server.private_ip
}

output "ec2_public_ip" {
  description = "EC2 인스턴스의 퍼블릭 IP 주소"
  value       = aws_eip.app_server.public_ip
}

output "ec2_instance_state" {
  description = "EC2 인스턴스 상태"
  value       = aws_instance.app_server.instance_state
}

# S3 Outputs
output "static_files_bucket_name" {
  description = "정적 파일 저장용 S3 버킷 이름"
  value       = aws_s3_bucket.static_files.id
}

output "static_files_bucket_arn" {
  description = "정적 파일 저장용 S3 버킷 ARN"
  value       = aws_s3_bucket.static_files.arn
}

# Terraform State는 S3에 저장됩니다 (공유 버킷)
# State 파일 위치:
#   s3://penguin-land-shared-tfstate/env:/{workspace}/terraform.tfstate

# DynamoDB Outputs
# Lock 테이블은 bootstrap-backend.sh로 생성되며 Terraform으로 관리하지 않음
# 테이블명: penguin-land-shared-tflock

output "app_data_table_name" {
  description = "애플리케이션 데이터 저장용 DynamoDB 테이블 이름"
  value       = aws_dynamodb_table.app_data.name
}

output "metrics_table_name" {
  description = "메트릭 데이터 저장용 DynamoDB 테이블 이름"
  value       = aws_dynamodb_table.metrics.name
}

# Lambda Outputs
output "lambda_function_name" {
  description = "알람 처리용 Lambda 함수 이름"
  value       = aws_lambda_function.alarm_processor.function_name
}

output "lambda_function_arn" {
  description = "Lambda 함수 ARN"
  value       = aws_lambda_function.alarm_processor.arn
}

# SNS Outputs
output "sns_topic_arn" {
  description = "알람 수신용 SNS 토픽 ARN"
  value       = aws_sns_topic.alarms.arn
}

output "sns_topic_name" {
  description = "SNS 토픽 이름"
  value       = aws_sns_topic.alarms.name
}

# CloudWatch Outputs
output "cloudwatch_dashboard_name" {
  description = "CloudWatch 대시보드 이름"
  value       = aws_cloudwatch_dashboard.main.dashboard_name
}

output "cloudwatch_log_group_name" {
  description = "EC2 로그 그룹 이름"
  value       = aws_cloudwatch_log_group.ec2_app.name
}

# IAM Outputs
output "ec2_iam_role_name" {
  description = "EC2 인스턴스 IAM Role 이름"
  value       = aws_iam_role.ec2_role.name
}

output "lambda_iam_role_name" {
  description = "Lambda 함수 IAM Role 이름"
  value       = aws_iam_role.lambda_role.name
}

# Session Info
output "session_id" {
  description = "현재 세션 ID"
  value       = var.session_id
}

output "project_name" {
  description = "프로젝트 이름"
  value       = var.project_name
}

# Application URLs
output "application_url" {
  description = "애플리케이션 접속 URL (HTTP)"
  value       = "http://${aws_eip.app_server.public_ip}:8080"
}

output "cloudwatch_dashboard_url" {
  description = "CloudWatch 대시보드 URL"
  value       = "https://console.aws.amazon.com/cloudwatch/home?region=${var.aws_region}#dashboards:name=${aws_cloudwatch_dashboard.main.dashboard_name}"
}

# Summary Output
output "deployment_summary" {
  description = "배포 요약 정보"
  value = {
    session_id            = var.session_id
    region                = var.aws_region
    ec2_public_ip         = aws_eip.app_server.public_ip
    nat_gateway_public_ip = aws_eip.nat.public_ip
    application_url       = "http://${aws_eip.app_server.public_ip}:8080"
    vpc_id                = aws_vpc.main.id
    resources_created     = "VPC(+NAT Gateway), EC2, S3(2), DynamoDB(3), Lambda, SNS, CloudWatch"
    dashboard_url         = "https://console.aws.amazon.com/cloudwatch/home?region=${var.aws_region}#dashboards:name=${aws_cloudwatch_dashboard.main.dashboard_name}"
  }
}
