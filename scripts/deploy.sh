#!/bin/bash
set -e

# Configuration
AWS_REGION="ap-northeast-2"
ECR_REPO="petstar"
IMAGE_TAG="${1:-latest}"

# Get AWS account ID
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO}"

echo "=== PetStar Deployment Script ==="
echo "ECR URL: ${ECR_URL}"
echo "Image Tag: ${IMAGE_TAG}"
echo ""

# Step 1: Build
echo "[1/4] Building application..."
cd "$(dirname "$0")/.."
./gradlew clean build -x test

# Step 2: Docker build
echo "[2/4] Building Docker image..."
docker build -t ${ECR_REPO}:${IMAGE_TAG} .

# Step 3: ECR login & push
echo "[3/4] Pushing to ECR..."
aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com
docker tag ${ECR_REPO}:${IMAGE_TAG} ${ECR_URL}:${IMAGE_TAG}
docker push ${ECR_URL}:${IMAGE_TAG}

echo "[4/4] Done! Image pushed to ECR."
echo ""
echo "To deploy on EC2, SSH into the app server and run:"
echo "  docker pull ${ECR_URL}:${IMAGE_TAG}"
echo "  docker-compose up -d"
