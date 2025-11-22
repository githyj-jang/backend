variable "aws_region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2" # 한국-서울
}

variable "session_id" {
  description = "각 배포를 구분하는 고유한 세션 ID"
  type        = string
}

variable "project_name" {
  description = "프로젝트 이름"
  type        = string
  default     = "penguin-land"
}

variable "environment" {
  description = "환경 (dev, staging, prod)"
  type        = string
  default     = "dev"
}

variable "ec2_instance_type" {
  description = "EC2 인스턴스 타입"
  type        = string
  default     = "t2.micro"
}

variable "ec2_key_name" {
  description = "EC2 인스턴스 접속용 키페어 이름"
  type        = string
  default     = ""
}

variable "alert_email" {
  description = "알람을 받을 이메일 주소"
  type        = string
  default     = ""
}

variable "cpu_warning_threshold" {
  description = "CPU 사용률 경고 임계값 (%)"
  type        = number
  default     = 50
}

variable "cpu_critical_threshold" {
  description = "CPU 사용률 위험 임계값 (%)"
  type        = number
  default     = 70
}

variable "error_rate_warning_threshold" {
  description = "5xx 에러율 경고 임계값 (%)"
  type        = number
  default     = 3
}

variable "error_rate_critical_threshold" {
  description = "5xx 에러율 위험 임계값 (%)"
  type        = number
  default     = 5
}

variable "latency_warning_threshold" {
  description = "레이턴시 경고 임계값 (ms)"
  type        = number
  default     = 400
}

variable "latency_critical_threshold" {
  description = "레이턴시 위험 임계값 (ms)"
  type        = number
  default     = 700
}

variable "common_tags" {
  description = "모든 리소스에 적용될 공통 태그"
  type        = map(string)
  default = {
    Project   = "penguin-land"
    ManagedBy = "terraform"
  }
}
