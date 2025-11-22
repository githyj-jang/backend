# Archive Lambda function code
data "archive_file" "alarm_processor" {
  type        = "zip"
  source_file = "${path.module}/lambda/alarm_processor.py"
  output_path = "${path.module}/lambda/alarm_processor.zip"
}

# Lambda Function for Alarm Processing
resource "aws_lambda_function" "alarm_processor" {
  filename         = data.archive_file.alarm_processor.output_path
  function_name    = "${var.project_name}-${var.session_id}-alarm-processor"
  role             = aws_iam_role.lambda_role.arn
  handler          = "alarm_processor.lambda_handler"
  source_code_hash = data.archive_file.alarm_processor.output_base64sha256
  runtime          = "python3.11"
  timeout          = 30
  memory_size      = 256

  environment {
    variables = {
      SESSION_ID           = var.session_id
      METRICS_TABLE_NAME   = aws_dynamodb_table.metrics.name
      CLOUDWATCH_NAMESPACE = "PenguinLand/${var.session_id}"
      PROJECT_NAME         = var.project_name
    }
  }

  vpc_config {
    subnet_ids         = [aws_subnet.private.id]
    security_group_ids = [aws_security_group.lambda.id]
  }

  tags = {
    Name    = "${var.project_name}-${var.session_id}-alarm-processor"
    Purpose = "Process CloudWatch alarms and calculate risk score"
  }
}

# Lambda Permission for SNS
resource "aws_lambda_permission" "allow_sns" {
  statement_id  = "AllowExecutionFromSNS"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.alarm_processor.function_name
  principal     = "sns.amazonaws.com"
  source_arn    = aws_sns_topic.alarms.arn
}

# CloudWatch Log Group for Lambda
resource "aws_cloudwatch_log_group" "alarm_processor" {
  name              = "/aws/lambda/${aws_lambda_function.alarm_processor.function_name}"
  retention_in_days = 7

  tags = {
    Name = "${var.project_name}-${var.session_id}-alarm-processor-logs"
  }
}
