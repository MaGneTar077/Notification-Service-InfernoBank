# IAM Role para Lambda de errores
resource "aws_iam_role" "iam_for_lambda_error" {
  name               = "execution_role_send_notifications_error_lambda"
  assume_role_policy = data.aws_iam_policy_document.assume_role_error.json
}

# Attach basic execution role
resource "aws_iam_role_policy_attachment" "lambda_basic_execution_error" {
  role       = aws_iam_role.iam_for_lambda_error.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# Attach custom policy
resource "aws_iam_role_policy" "iam_policy_for_lambda_error" {
  name   = "lambda_notifications_error_policy"
  role   = aws_iam_role.iam_for_lambda_error.id
  policy = data.aws_iam_policy_document.lambda_execution_error.json
}

# DynamoDB para errores
resource "aws_dynamodb_table" "notification_error_table" {
  name         = "notification-error-table"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "uuid"
  range_key    = "createdAt"

  attribute {
    name = "uuid"
    type = "S"
  }

  attribute {
    name = "createdAt"
    type = "S"
  }
}

# Lambda de errores
resource "aws_lambda_function" "send_notifications_error_lambda" {
  function_name = var.lambda_error_name
  filename      = abspath("${path.module}/../send-notifications-error-lambda/target/${var.file_name_error}")
  handler       = "org.example.SendNotificationsErrorLambda::handleRequest"
  runtime       = "java17"
  timeout       = 20
  memory_size   = 512
  role          = aws_iam_role.iam_for_lambda_error.arn

  source_code_hash = filebase64sha256(abspath("${path.module}/../send-notifications-error-lambda/target/${var.file_name_error}"))

  environment {
    variables = {
      DLQ_URL                = data.aws_sqs_queue.notification_error_dlq.url
      NOTIFICATION_ERROR_TABLE = aws_dynamodb_table.notification_error_table.name
    }
  }
}

# Vincular la DLQ con la Lambda de errores
resource "aws_lambda_event_source_mapping" "sqs_trigger_error" {
  event_source_arn = data.aws_sqs_queue.notification_error_dlq.arn
  function_name    = aws_lambda_function.send_notifications_error_lambda.arn
  batch_size       = 10
}

# Outputs
output "notification_error_table_name" {
  value = aws_dynamodb_table.notification_error_table.name
}

output "notification_error_dlq_url" {
  value = data.aws_sqs_queue.notification_error_dlq.url
}
