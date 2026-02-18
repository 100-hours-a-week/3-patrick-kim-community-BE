output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.main.id
}

output "app_server_public_ip" {
  description = "App server public IP"
  value       = aws_eip.app.public_ip
}

output "monitoring_server_public_ip" {
  description = "Monitoring server public IP"
  value       = aws_eip.monitoring.public_ip
}

output "rds_endpoint" {
  description = "RDS endpoint"
  value       = aws_db_instance.main.endpoint
}

output "rds_address" {
  description = "RDS address (without port)"
  value       = aws_db_instance.main.address
}

output "ecr_repository_url" {
  description = "ECR repository URL"
  value       = aws_ecr_repository.app.repository_url
}

output "ecr_registry_id" {
  description = "ECR registry ID"
  value       = aws_ecr_repository.app.registry_id
}

# Connection strings
output "ssh_app_command" {
  description = "SSH command to app server"
  value       = "ssh -i ~/.ssh/${var.key_name}.pem ec2-user@${aws_eip.app.public_ip}"
}

output "ssh_monitoring_command" {
  description = "SSH command to monitoring server"
  value       = "ssh -i ~/.ssh/${var.key_name}.pem ec2-user@${aws_eip.monitoring.public_ip}"
}

output "grafana_url" {
  description = "Grafana URL"
  value       = "http://${aws_eip.monitoring.public_ip}:3000"
}

output "prometheus_url" {
  description = "Prometheus URL"
  value       = "http://${aws_eip.monitoring.public_ip}:9090"
}

output "app_url" {
  description = "Application URL"
  value       = "http://${aws_eip.app.public_ip}:8080"
}
