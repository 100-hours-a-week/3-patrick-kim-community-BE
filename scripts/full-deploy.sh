#!/bin/bash
set -e

# ============================================
# PetStar 전체 배포 스크립트
#
# 사용법: ./scripts/full-deploy.sh
#
# 수행 작업:
#   1. 앱 빌드 + Docker 이미지 ECR 푸시
#   2. 모니터링 서버 설정 (Redis + Prometheus + Grafana)
#   3. 앱 서버 배포 (Docker pull + run)
#   4. DB 스키마 생성 (JPA ddl-auto)
#   5. Health check
# ============================================

# === Configuration ===
AWS_REGION="ap-northeast-2"
AWS_PROFILE="patrick"
ECR_REPO="petstar"
IMAGE_TAG="${1:-latest}"

APP_SERVER="3.34.221.62"
MONITORING_SERVER="15.164.138.22"
SSH_KEY="~/.ssh/patrick-keypair.pem"
SSH_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=10"

RDS_HOST="petstar-db.cpwka4iukgmy.ap-northeast-2.rds.amazonaws.com"
DB_NAME="petstar"
DB_USERNAME="admin"
DB_PASSWORD="PetStar2026!"

AWS_ACCOUNT_ID=$(aws --profile ${AWS_PROFILE} sts get-caller-identity --query Account --output text)
ECR_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO}"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "============================================"
echo " PetStar Full Deployment"
echo "============================================"
echo " App Server:       ${APP_SERVER}"
echo " Monitoring:       ${MONITORING_SERVER}"
echo " RDS:              ${RDS_HOST}"
echo " ECR:              ${ECR_URL}:${IMAGE_TAG}"
echo "============================================"
echo ""

# === Step 1: Build & Push ===
echo "[1/5] Building application..."
cd "${PROJECT_ROOT}"
./gradlew clean build -x test --quiet

echo "[1/5] Building Docker image..."
docker build --platform linux/amd64 -t ${ECR_REPO}:${IMAGE_TAG} .

echo "[1/5] Pushing to ECR..."
aws --profile ${AWS_PROFILE} ecr get-login-password --region ${AWS_REGION} \
  | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com
docker tag ${ECR_REPO}:${IMAGE_TAG} ${ECR_URL}:${IMAGE_TAG}
docker push ${ECR_URL}:${IMAGE_TAG}
echo "[1/5] ✅ Image pushed to ECR"
echo ""

# === Step 2: Monitoring Server Setup (Redis + Prometheus + Grafana) ===
echo "[2/5] Setting up monitoring server..."
ssh ${SSH_OPTS} -i ${SSH_KEY} ec2-user@${MONITORING_SERVER} << 'MONITORING_EOF'
  # Docker가 설치될 때까지 대기
  for i in {1..30}; do
    if command -v docker &> /dev/null; then break; fi
    echo "Waiting for Docker installation... ($i)"
    sleep 5
  done

  # Docker 시작
  sudo systemctl start docker 2>/dev/null || true
  sudo systemctl enable docker 2>/dev/null || true
  sudo usermod -aG docker ec2-user 2>/dev/null || true

  # Docker Compose 설치 확인
  if ! command -v docker-compose &> /dev/null; then
    sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
    sudo chmod +x /usr/local/bin/docker-compose
  fi

  # Redis 컨테이너 실행
  sudo docker rm -f petstar-redis 2>/dev/null || true
  sudo docker run -d --name petstar-redis \
    -p 6379:6379 \
    --restart unless-stopped \
    redis:7-alpine

  # Prometheus + Grafana 디렉토리
  mkdir -p ~/monitoring
MONITORING_EOF

# Prometheus 설정 파일 전송
scp ${SSH_OPTS} -i ${SSH_KEY} \
  "${PROJECT_ROOT}/monitoring/prometheus.yml" \
  ec2-user@${MONITORING_SERVER}:~/monitoring/prometheus.yml

scp ${SSH_OPTS} -i ${SSH_KEY} \
  "${PROJECT_ROOT}/monitoring/docker-compose.yml" \
  ec2-user@${MONITORING_SERVER}:~/monitoring/docker-compose.yml

# Prometheus + Grafana 실행
ssh ${SSH_OPTS} -i ${SSH_KEY} ec2-user@${MONITORING_SERVER} << 'MONITORING2_EOF'
  cd ~/monitoring
  sudo docker-compose down 2>/dev/null || true
  sudo docker-compose up -d
MONITORING2_EOF

echo "[2/5] ✅ Monitoring server ready (Redis + Prometheus + Grafana)"
echo ""

# === Step 3: App Server Deploy ===
echo "[3/5] Deploying app to server..."
ssh ${SSH_OPTS} -i ${SSH_KEY} ec2-user@${APP_SERVER} << DEPLOY_EOF
  # Docker 대기
  for i in {1..30}; do
    if command -v docker &> /dev/null; then break; fi
    echo "Waiting for Docker installation... (\$i)"
    sleep 5
  done

  sudo systemctl start docker 2>/dev/null || true
  sudo systemctl enable docker 2>/dev/null || true
  sudo usermod -aG docker ec2-user 2>/dev/null || true

  # ECR 로그인
  aws ecr get-login-password --region ${AWS_REGION} \
    | sudo docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com

  # 기존 컨테이너 정리
  sudo docker rm -f petstar-app 2>/dev/null || true

  # 이미지 풀
  sudo docker pull ${ECR_URL}:${IMAGE_TAG}

  # 앱 실행
  sudo docker run -d --name petstar-app \
    -p 8080:8080 \
    --restart unless-stopped \
    -e SPRING_PROFILES_ACTIVE=dev \
    -e SPRING_DATASOURCE_URL="jdbc:mysql://${RDS_HOST}:3306/${DB_NAME}?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true" \
    -e SPRING_DATASOURCE_USERNAME="${DB_USERNAME}" \
    -e SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
    -e REDIS_HOST="10.0.2.166" \
    -e REDIS_PORT=6379 \
    -e JWT_SECRET="petstar-jwt-secret-key-for-development-2026" \
    -e AWS_REGION="${AWS_REGION}" \
    ${ECR_URL}:${IMAGE_TAG}
DEPLOY_EOF

echo "[3/5] ✅ App container started"
echo ""

# === Step 4: Wait for app startup ===
echo "[4/5] Waiting for app startup..."
for i in {1..30}; do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://${APP_SERVER}:8080/health 2>/dev/null || echo "000")
  if [ "${HTTP_CODE}" = "200" ]; then
    echo "[4/5] ✅ App is healthy!"
    break
  fi
  echo "  Waiting... (${i}/30) HTTP=${HTTP_CODE}"
  sleep 10
done

if [ "${HTTP_CODE}" != "200" ]; then
  echo "[4/5] ⚠️  App not responding yet. Check logs:"
  echo "  ssh -i ${SSH_KEY} ec2-user@${APP_SERVER} 'sudo docker logs petstar-app --tail 50'"
fi
echo ""

# === Step 5: Summary ===
echo "============================================"
echo " Deployment Complete!"
echo "============================================"
echo " App:        http://${APP_SERVER}:8080/health"
echo " Grafana:    http://${MONITORING_SERVER}:3000 (admin/petstar123)"
echo " Prometheus: http://${MONITORING_SERVER}:9090"
echo ""
echo " SSH App:        ssh -i ${SSH_KEY} ec2-user@${APP_SERVER}"
echo " SSH Monitoring:  ssh -i ${SSH_KEY} ec2-user@${MONITORING_SERVER}"
echo ""
echo " Next: DB 시딩 (테스트 데이터 적재)"
echo "   ssh -i ${SSH_KEY} ec2-user@${APP_SERVER}"
echo "   mysql -h ${RDS_HOST} -u ${DB_USERNAME} -p${DB_PASSWORD} ${DB_NAME} < seed-data.sql"
echo "============================================"
