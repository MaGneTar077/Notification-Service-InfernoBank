variable "lambda_error_name" {
  description = "Nombre de la función Lambda de errores"
  type        = string
  default     = "send-notifications-error-lambda"
}

variable "file_name_error" {
  description = "Nombre del archivo JAR de la Lambda de errores"
  type        = string
  default     = "send-notifications-error-lambda-1.0-SNAPSHOT.jar"
}
