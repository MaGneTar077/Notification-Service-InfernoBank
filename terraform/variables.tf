variable "lambda_name" {
  description = "Nombre de la funcion Lambda"
  type        = string
  default     = "send-notifications-lambda"
}

variable "file_name" {
  description = "Nombre del archivo JAR de la Lambda"
  type        = string
  default     = "send-notification-lambda-1.0-SNAPSHOT.jar"
}
