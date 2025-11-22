# CloudWatch Log Group for EC2
resource "aws_cloudwatch_log_group" "ec2_app" {
  name              = "/aws/ec2/penguin-land/${var.session_id}"
  retention_in_days = 7

  tags = {
    Name = "${var.project_name}-${var.session_id}-ec2-logs"
  }
}

# ===== CPU Utilization Alarms =====

# CPU Warning Alarm (50% 이상)
resource "aws_cloudwatch_metric_alarm" "cpu_warning" {
  alarm_name          = "${var.project_name}-${var.session_id}-cpu-warning"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "CPUUtilization"
  namespace           = "AWS/EC2"
  period              = 300
  statistic           = "Average"
  threshold           = var.cpu_warning_threshold
  alarm_description   = "CPU 사용률이 ${var.cpu_warning_threshold}%를 초과했습니다"
  treat_missing_data  = "notBreaching"

  dimensions = {
    InstanceId = aws_instance.app_server.id
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]

  tags = {
    Name     = "${var.project_name}-${var.session_id}-cpu-warning"
    Severity = "warning"
  }
}

# CPU Critical Alarm (70% 이상)
resource "aws_cloudwatch_metric_alarm" "cpu_critical" {
  alarm_name          = "${var.project_name}-${var.session_id}-cpu-critical"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "CPUUtilization"
  namespace           = "AWS/EC2"
  period              = 300
  statistic           = "Average"
  threshold           = var.cpu_critical_threshold
  alarm_description   = "CPU 사용률이 ${var.cpu_critical_threshold}%를 초과했습니다 (위험)"
  treat_missing_data  = "notBreaching"

  dimensions = {
    InstanceId = aws_instance.app_server.id
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]

  tags = {
    Name     = "${var.project_name}-${var.session_id}-cpu-critical"
    Severity = "critical"
  }
}

# ===== Memory Utilization Alarm (Custom Metric) =====

resource "aws_cloudwatch_metric_alarm" "memory_warning" {
  alarm_name          = "${var.project_name}-${var.session_id}-memory-warning"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "MEM_USED"
  namespace           = "PenguinLand/${var.session_id}"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "메모리 사용률이 80%를 초과했습니다"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]

  tags = {
    Name     = "${var.project_name}-${var.session_id}-memory-warning"
    Severity = "warning"
  }
}

# ===== Status Check Alarms =====

# EC2 Instance Status Check Failed
resource "aws_cloudwatch_metric_alarm" "instance_status_check" {
  alarm_name          = "${var.project_name}-${var.session_id}-instance-status-check"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "StatusCheckFailed_Instance"
  namespace           = "AWS/EC2"
  period              = 60
  statistic           = "Average"
  threshold           = 0
  alarm_description   = "EC2 인스턴스 상태 체크에 실패했습니다"
  treat_missing_data  = "notBreaching"

  dimensions = {
    InstanceId = aws_instance.app_server.id
  }

  alarm_actions = [aws_sns_topic.alarms.arn]

  tags = {
    Name     = "${var.project_name}-${var.session_id}-instance-status-check"
    Severity = "critical"
  }
}

# EC2 System Status Check Failed
resource "aws_cloudwatch_metric_alarm" "system_status_check" {
  alarm_name          = "${var.project_name}-${var.session_id}-system-status-check"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "StatusCheckFailed_System"
  namespace           = "AWS/EC2"
  period              = 60
  statistic           = "Average"
  threshold           = 0
  alarm_description   = "EC2 시스템 상태 체크에 실패했습니다"
  treat_missing_data  = "notBreaching"

  dimensions = {
    InstanceId = aws_instance.app_server.id
  }

  alarm_actions = [aws_sns_topic.alarms.arn]

  tags = {
    Name     = "${var.project_name}-${var.session_id}-system-status-check"
    Severity = "critical"
  }
}

# ===== Disk Utilization Alarm =====

resource "aws_cloudwatch_metric_alarm" "disk_warning" {
  alarm_name          = "${var.project_name}-${var.session_id}-disk-warning"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "DISK_USED"
  namespace           = "PenguinLand/${var.session_id}"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "디스크 사용률이 80%를 초과했습니다"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]

  tags = {
    Name     = "${var.project_name}-${var.session_id}-disk-warning"
    Severity = "warning"
  }
}

# ===== Lambda Error Rate Alarm =====

resource "aws_cloudwatch_metric_alarm" "lambda_errors" {
  alarm_name          = "${var.project_name}-${var.session_id}-lambda-errors"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "Errors"
  namespace           = "AWS/Lambda"
  period              = 300
  statistic           = "Sum"
  threshold           = 5
  alarm_description   = "Lambda 함수에서 5개 이상의 에러가 발생했습니다"
  treat_missing_data  = "notBreaching"

  dimensions = {
    FunctionName = aws_lambda_function.alarm_processor.function_name
  }

  alarm_actions = [aws_sns_topic.alarms.arn]

  tags = {
    Name     = "${var.project_name}-${var.session_id}-lambda-errors"
    Severity = "warning"
  }
}

# ===== Lambda Throttle Alarm =====

resource "aws_cloudwatch_metric_alarm" "lambda_throttles" {
  alarm_name          = "${var.project_name}-${var.session_id}-lambda-throttles"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "Throttles"
  namespace           = "AWS/Lambda"
  period              = 300
  statistic           = "Sum"
  threshold           = 0
  alarm_description   = "Lambda 함수가 스로틀링되고 있습니다"
  treat_missing_data  = "notBreaching"

  dimensions = {
    FunctionName = aws_lambda_function.alarm_processor.function_name
  }

  alarm_actions = [aws_sns_topic.alarms.arn]

  tags = {
    Name     = "${var.project_name}-${var.session_id}-lambda-throttles"
    Severity = "critical"
  }
}

# ===== 5xx Error Rate Alarms (커스텀 메트릭) =====
# Spring Boot에서 다음 메트릭을 전송해야 합니다:
# - Namespace: PenguinLand/${session_id}
# - Metric: ErrorRate5xx (단위: Percent)

resource "aws_cloudwatch_metric_alarm" "error_rate_warning" {
  alarm_name          = "${var.project_name}-${var.session_id}-error-rate-warning"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "ErrorRate5xx"
  namespace           = "PenguinLand/${var.session_id}"
  period              = 300
  statistic           = "Average"
  threshold           = var.error_rate_warning_threshold
  alarm_description   = "5xx 에러율이 ${var.error_rate_warning_threshold}%를 초과했습니다"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]

  tags = {
    Name     = "${var.project_name}-${var.session_id}-error-rate-warning"
    Severity = "warning"
  }
}

resource "aws_cloudwatch_metric_alarm" "error_rate_critical" {
  alarm_name          = "${var.project_name}-${var.session_id}-error-rate-critical"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "ErrorRate5xx"
  namespace           = "PenguinLand/${var.session_id}"
  period              = 300
  statistic           = "Average"
  threshold           = var.error_rate_critical_threshold
  alarm_description   = "5xx 에러율이 ${var.error_rate_critical_threshold}%를 초과했습니다 (위험)"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]

  tags = {
    Name     = "${var.project_name}-${var.session_id}-error-rate-critical"
    Severity = "critical"
  }
}

# ===== Latency Alarms (커스텀 메트릭) =====
# Spring Boot에서 다음 메트릭을 전송해야 합니다:
# - Namespace: PenguinLand/${session_id}
# - Metric: ResponseTimeMs (단위: Milliseconds)

resource "aws_cloudwatch_metric_alarm" "latency_warning" {
  alarm_name          = "${var.project_name}-${var.session_id}-latency-warning"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "ResponseTimeMs"
  namespace           = "PenguinLand/${var.session_id}"
  period              = 300
  statistic           = "Average"
  threshold           = var.latency_warning_threshold
  alarm_description   = "응답 시간이 ${var.latency_warning_threshold}ms를 초과했습니다"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]

  tags = {
    Name     = "${var.project_name}-${var.session_id}-latency-warning"
    Severity = "warning"
  }
}

resource "aws_cloudwatch_metric_alarm" "latency_critical" {
  alarm_name          = "${var.project_name}-${var.session_id}-latency-critical"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "ResponseTimeMs"
  namespace           = "PenguinLand/${var.session_id}"
  period              = 300
  statistic           = "Average"
  threshold           = var.latency_critical_threshold
  alarm_description   = "응답 시간이 ${var.latency_critical_threshold}ms를 초과했습니다 (위험)"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]

  tags = {
    Name     = "${var.project_name}-${var.session_id}-latency-critical"
    Severity = "critical"
  }
}

# ===== Composite Alarm (종합 알람) =====

resource "aws_cloudwatch_composite_alarm" "system_health" {
  alarm_name        = "${var.project_name}-${var.session_id}-system-health"
  alarm_description = "시스템 전체 상태를 나타내는 종합 알람"
  actions_enabled   = true
  alarm_actions     = [aws_sns_topic.alarms.arn]
  ok_actions        = [aws_sns_topic.alarms.arn]

  alarm_rule = "ALARM(${aws_cloudwatch_metric_alarm.cpu_critical.alarm_name}) OR ALARM(${aws_cloudwatch_metric_alarm.error_rate_critical.alarm_name}) OR ALARM(${aws_cloudwatch_metric_alarm.latency_critical.alarm_name}) OR ALARM(${aws_cloudwatch_metric_alarm.instance_status_check.alarm_name}) OR ALARM(${aws_cloudwatch_metric_alarm.system_status_check.alarm_name})"

  tags = {
    Name     = "${var.project_name}-${var.session_id}-system-health"
    Severity = "critical"
  }
}

# ===== CloudWatch Dashboard =====

resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = "${var.project_name}-${var.session_id}-dashboard"

  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric"
        properties = {
          metrics = [
            ["AWS/EC2", "CPUUtilization", { stat = "Average", label = "CPU 사용률" }]
          ]
          period = 300
          region = var.aws_region
          title  = "EC2 CPU 사용률"
          yAxis = {
            left = {
              min = 0
              max = 100
            }
          }
        }
      },
      {
        type = "metric"
        properties = {
          metrics = [
            ["PenguinLand/${var.session_id}", "MEM_USED", { stat = "Average", label = "메모리 사용률" }]
          ]
          period = 300
          region = var.aws_region
          title  = "메모리 사용률"
          yAxis = {
            left = {
              min = 0
              max = 100
            }
          }
        }
      },
      {
        type = "metric"
        properties = {
          metrics = [
            ["PenguinLand/${var.session_id}", "RiskScore", { stat = "Average", label = "위험 점수" }]
          ]
          period = 300
          region = var.aws_region
          title  = "위험 점수 (Penguin Status)"
          yAxis = {
            left = {
              min = 0
              max = 100
            }
          }
        }
      },
      {
        type = "metric"
        properties = {
          metrics = [
            ["AWS/Lambda", "Invocations", { stat = "Sum", label = "Lambda 호출" }],
            [".", "Errors", { stat = "Sum", label = "Lambda 에러" }]
          ]
          period = 300
          region = var.aws_region
          title  = "Lambda 함수 메트릭"
        }
      },
      {
        type = "metric"
        properties = {
          metrics = [
            ["PenguinLand/${var.session_id}", "ErrorRate5xx", { stat = "Average", label = "5xx 에러율" }]
          ]
          period = 300
          region = var.aws_region
          title  = "5xx 에러율 (%)"
          annotations = {
            horizontal = [
              {
                label = "경고 (${var.error_rate_warning_threshold}%)"
                value = var.error_rate_warning_threshold
                color = "#ff9900"
              },
              {
                label = "위험 (${var.error_rate_critical_threshold}%)"
                value = var.error_rate_critical_threshold
                color = "#d13212"
              }
            ]
          }
          yAxis = {
            left = {
              min = 0
            }
          }
        }
      },
      {
        type = "metric"
        properties = {
          metrics = [
            ["PenguinLand/${var.session_id}", "ResponseTimeMs", { stat = "Average", label = "평균 응답 시간" }],
            ["...", { stat = "Maximum", label = "최대 응답 시간" }]
          ]
          period = 300
          region = var.aws_region
          title  = "응답 시간 (ms)"
          annotations = {
            horizontal = [
              {
                label = "경고 (${var.latency_warning_threshold}ms)"
                value = var.latency_warning_threshold
                color = "#ff9900"
              },
              {
                label = "위험 (${var.latency_critical_threshold}ms)"
                value = var.latency_critical_threshold
                color = "#d13212"
              }
            ]
          }
          yAxis = {
            left = {
              min = 0
            }
          }
        }
      }
    ]
  })
}
