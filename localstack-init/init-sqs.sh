#!/bin/bash
# LocalStack SQS 초기화 스크립트
# Phase 10: 투표 처리용 큐 생성

echo "Creating SQS queues..."

# Dead Letter Queue 먼저 생성
awslocal sqs create-queue \
    --queue-name petstar-votes-dlq \
    --attributes '{"MessageRetentionPeriod":"1209600"}'

# DLQ ARN 가져오기
DLQ_ARN=$(awslocal sqs get-queue-attributes \
    --queue-url http://localhost:4566/000000000000/petstar-votes-dlq \
    --attribute-names QueueArn \
    --query 'Attributes.QueueArn' \
    --output text)

echo "DLQ ARN: $DLQ_ARN"

# Main Vote Queue 생성 (DLQ 연결)
awslocal sqs create-queue \
    --queue-name petstar-votes \
    --attributes "{
        \"VisibilityTimeout\":\"30\",
        \"MessageRetentionPeriod\":\"86400\",
        \"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"
    }"

echo "SQS queues created successfully!"

# 큐 목록 확인
awslocal sqs list-queues
