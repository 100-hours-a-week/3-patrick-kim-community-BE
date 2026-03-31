#!/bin/bash
# ============================================
# PetStar 부하테스트 실행 스크립트
# ============================================
#
# 사용법:
#   ./run-test.sh smoke      # 기본 동작 확인 (5 VUs, 30s)
#   ./run-test.sh quick      # 빠른 테스트 (10 VUs, 1m)
#   ./run-test.sh before     # Before 측정 (50/100/200 VUs)
#   ./run-test.sh load       # 점진적 부하 (50→100→200 VUs)
#   ./run-test.sh stress     # 스트레스 테스트 (300 VUs, 5m)
#   ./run-test.sh phase9     # Phase 9 최종 테스트 (10→100→200→300 VUs)
#
# 환경변수:
#   BASE_URL: 테스트 대상 서버 (기본값: http://43.200.83.214:8080)

set -e

BASE_URL="${BASE_URL:-http://43.200.83.214:8080}"
SCENARIO="${1:-smoke}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

mkdir -p results

echo "=========================================="
echo "PetStar 부하테스트"
echo "=========================================="
echo "Base URL: $BASE_URL"
echo "Scenario: $SCENARIO"
echo "Time: $(date '+%Y-%m-%d %H:%M:%S')"
echo "=========================================="
echo ""

case $SCENARIO in
    smoke)
        echo "=== Smoke Test ==="
        echo "목적: 기본 동작 확인"
        echo "VUs: 5, Duration: 30초"
        echo ""
        k6 run \
            -e BASE_URL=$BASE_URL \
            --vus 5 \
            --duration 30s \
            "$SCRIPT_DIR/01_basic-load-test.js"
        ;;

    quick)
        echo "=== Quick Test ==="
        echo "목적: 빠른 성능 확인"
        echo "VUs: 10, Duration: 1분"
        echo ""
        k6 run \
            -e BASE_URL=$BASE_URL \
            --vus 10 \
            --duration 1m \
            "$SCRIPT_DIR/01_basic-load-test.js"
        ;;

    before)
        echo "=== Before Measurement ==="
        echo "목적: 최적화 전 베이스라인 측정"
        echo "단계: 50 VUs → 100 VUs → 200 VUs"
        echo ""

        echo "--- Phase 1: 50 VUs (2분) ---"
        k6 run -e BASE_URL=$BASE_URL --vus 50 --duration 2m \
            --out json=results/before-50vus-$(date +%Y%m%d-%H%M%S).json \
            "$SCRIPT_DIR/01_basic-load-test.js"

        echo ""
        echo "--- Phase 2: 100 VUs (2분) ---"
        k6 run -e BASE_URL=$BASE_URL --vus 100 --duration 2m \
            --out json=results/before-100vus-$(date +%Y%m%d-%H%M%S).json \
            "$SCRIPT_DIR/01_basic-load-test.js"

        echo ""
        echo "--- Phase 3: 200 VUs (2분) ---"
        k6 run -e BASE_URL=$BASE_URL --vus 200 --duration 2m \
            --out json=results/before-200vus-$(date +%Y%m%d-%H%M%S).json \
            "$SCRIPT_DIR/01_basic-load-test.js"
        ;;

    load)
        echo "=== Load Test ==="
        echo "목적: 점진적 부하 테스트"
        echo "패턴: Ramp up → Steady → Ramp down"
        echo ""
        k6 run \
            -e BASE_URL=$BASE_URL \
            --stage 1m:50,2m:100,2m:200,1m:100,1m:50,30s:0 \
            --out json=results/load-$(date +%Y%m%d-%H%M%S).json \
            "$SCRIPT_DIR/01_basic-load-test.js"
        ;;

    stress)
        echo "=== Stress Test ==="
        echo "목적: 시스템 한계점 파악"
        echo "VUs: 300, Duration: 5분"
        echo ""
        k6 run \
            -e BASE_URL=$BASE_URL \
            --vus 300 \
            --duration 5m \
            --out json=results/stress-$(date +%Y%m%d-%H%M%S).json \
            "$SCRIPT_DIR/01_basic-load-test.js"
        ;;

    phase9)
        echo "=== Phase 9 Final Test ==="
        echo "목적: 최종 성능 검증"
        echo "단계: Warmup(10) → Normal(100) → Peak(200) → Stress(300)"
        echo "총 시간: 5분 30초"
        echo ""
        k6 run \
            --out json=results/phase9-$(date +%Y%m%d-%H%M%S).json \
            "$SCRIPT_DIR/02_staged-load-test.js"
        ;;

    *)
        echo "Usage: ./run-test.sh [smoke|quick|before|load|stress|phase9]"
        echo ""
        echo "시나리오 설명:"
        echo "  smoke   - 기본 동작 확인 (5 VUs, 30초)"
        echo "  quick   - 빠른 테스트 (10 VUs, 1분)"
        echo "  before  - Before 측정 (50/100/200 VUs, 각 2분)"
        echo "  load    - 점진적 부하 (50→100→200→100→50 VUs)"
        echo "  stress  - 스트레스 테스트 (300 VUs, 5분)"
        echo "  phase9  - Phase 9 최종 테스트 (10→100→200→300 VUs)"
        exit 1
        ;;
esac

echo ""
echo "=========================================="
echo "테스트 완료!"
echo "=========================================="
echo "결과 파일: results/ 폴더"
echo "=========================================="
