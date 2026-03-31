# ============================================
# Amazon SQS - Vote Processing Queue
# ============================================
#
# Phase 10: 비동기 투표 처리를 위한 SQS 구성
#
# 아키텍처:
#   Client → API → Redis(즉시) → Response
#                     ↓
#                   SQS → Consumer → DB
#
# 선택 근거:
#   - Standard Queue: 순서 보장 불필요, 높은 처리량
#   - Idempotency: Consumer에서 중복 체크
#   - DLQ: 실패 메시지 별도 처리

# Main Vote Queue
resource "aws_sqs_queue" "votes" {
  name = "petstar-votes"

  # 메시지 설정
  visibility_timeout_seconds = 30    # Consumer 처리 시간
  message_retention_seconds  = 86400 # 1일 보관
  delay_seconds              = 0     # 즉시 사용 가능
  max_message_size           = 262144 # 256KB (충분)

  # Dead Letter Queue 연결
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.votes_dlq.arn
    maxReceiveCount     = 3 # 3번 실패 시 DLQ로 이동
  })

  tags = {
    Name    = "petstar-votes"
    Purpose = "Async vote processing"
  }
}

# Dead Letter Queue (실패 메시지 처리)
resource "aws_sqs_queue" "votes_dlq" {
  name = "petstar-votes-dlq"

  message_retention_seconds = 1209600 # 14일 보관 (디버깅용)

  tags = {
    Name    = "petstar-votes-dlq"
    Purpose = "Failed vote messages"
  }
}

# IAM Policy for SQS Access
resource "aws_iam_policy" "sqs_access" {
  name        = "petstar-sqs-access"
  description = "Allow PetStar to send and receive SQS messages"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "sqs:SendMessage",
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:GetQueueUrl"
        ]
        Resource = [
          aws_sqs_queue.votes.arn,
          aws_sqs_queue.votes_dlq.arn
        ]
      }
    ]
  })
}

# IAM Role for EC2 to access SQS
resource "aws_iam_role" "app_role" {
  name = "petstar-app-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
      }
    ]
  })
}

# Attach SQS policy to role
resource "aws_iam_role_policy_attachment" "sqs_policy" {
  role       = aws_iam_role.app_role.name
  policy_arn = aws_iam_policy.sqs_access.arn
}

# Instance profile for EC2
resource "aws_iam_instance_profile" "app_profile" {
  name = "petstar-app-profile"
  role = aws_iam_role.app_role.name
}

# CloudWatch Alarm for DLQ (실패 메시지 모니터링)
resource "aws_cloudwatch_metric_alarm" "dlq_messages" {
  alarm_name          = "petstar-votes-dlq-messages"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 300 # 5분
  statistic           = "Sum"
  threshold           = 10  # 10개 이상 실패 시 알람
  alarm_description   = "Vote processing failures detected"

  dimensions = {
    QueueName = aws_sqs_queue.votes_dlq.name
  }

  # SNS 알림 연결 (선택사항)
  # alarm_actions = [aws_sns_topic.alerts.arn]

  tags = {
    Name = "petstar-votes-dlq-alarm"
  }
}
