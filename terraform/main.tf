terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
  required_version = ">= 1.3.0"
}

# IAM Role para Lambda
resource "aws_iam_role" "iam_for_lambda" {
  name               = "execution_role_send_notifications_lambda"
  assume_role_policy = data.aws_iam_policy_document.assume_role.json
}

# Attach basic execution role
resource "aws_iam_role_policy_attachment" "lambda_basic_execution" {
  role       = aws_iam_role.iam_for_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# Attach custom policy
resource "aws_iam_role_policy" "iam_policy_for_lambda" {
  name   = "lambda_notifications_policy"
  role   = aws_iam_role.iam_for_lambda.id
  policy = data.aws_iam_policy_document.lambda_execution.json
}

# DynamoDB para notificaciones
resource "aws_dynamodb_table" "notification_table" {
  name         = "notification-table"
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

# Cola SQS DLQ
resource "aws_sqs_queue" "notification_dlq" {
  name                       = "error-send-notification-sqs"
  visibility_timeout_seconds = 30
}

# Cola SQS principal
data "aws_sqs_queue" "notification_sqs" {
  name = "notification-email-sqs"
}

# Bucket para plantillas HTML
resource "aws_s3_bucket" "templates_bucket" {
  bucket = "notification-templates-bucket"
}

resource "aws_s3_bucket_versioning" "templates_versioning" {
  bucket = aws_s3_bucket.templates_bucket.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Lambda
resource "aws_lambda_function" "send_notifications_lambda" {
  function_name = var.lambda_name
  filename      = abspath("${path.module}/../send-notifications-lambda/target/${var.file_name}")
  handler       = "org.example.SendNotificationsLambda::handleRequest"
  runtime       = "java17"
  timeout       = 20
  memory_size   = 512
  role          = aws_iam_role.iam_for_lambda.arn

  source_code_hash = filebase64sha256(abspath("${path.module}/../send-notifications-lambda/target/${var.file_name}"))

  environment {
    variables = {
      SQS_QUEUE_URL_NOTIFICATION = data.aws_sqs_queue.notification_sqs.url
      DLQ_URL                    = aws_sqs_queue.notification_dlq.url
      TEMPLATES_BUCKET           = aws_s3_bucket.templates_bucket.bucket
    }
  }
}

# Vincular la cola de notificaciones con la Lambda
resource "aws_lambda_event_source_mapping" "sqs_trigger" {
  event_source_arn = data.aws_sqs_queue.notification_sqs.arn
  function_name    = aws_lambda_function.send_notifications_lambda.arn
  batch_size       = 10
}

# Outputs
output "notification_table_name" {
  value = aws_dynamodb_table.notification_table.name
}

output "notification_dlq_url" {
  value = aws_sqs_queue.notification_dlq.url
}

output "templates_bucket_name" {
  value = aws_s3_bucket.templates_bucket.bucket
}
