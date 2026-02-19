#!/bin/bash
# k6 부하테스트 실행 스크립트

set -e

BASE_URL="${BASE_URL:-http://43.200.83.214:8080}"
SCENARIO="${1:-smoke}"  # smoke, load, stress

mkdir -p results

echo "=========================================="
echo "PetStar 부하테스트 실행"
echo "Base URL: $BASE_URL"
echo "Scenario: $SCENARIO"
echo "=========================================="

case $SCENARIO in
    smoke)
        echo "Smoke Test (5 VUs, 30s)"
        k6 run \
            -e BASE_URL=$BASE_URL \
            --out json=results/smoke-$(date +%Y%m%d-%H%M%S).json \
            --scenario smoke \
            load-test.js
        ;;
    load)
        echo "Load Test (50 → 100 → 200 VUs, 11m)"
        k6 run \
            -e BASE_URL=$BASE_URL \
            --out json=results/load-$(date +%Y%m%d-%H%M%S).json \
            --scenario load \
            load-test.js
        ;;
    stress)
        echo "Stress Test (Custom)"
        k6 run \
            -e BASE_URL=$BASE_URL \
            --vus 300 \
            --duration 5m \
            --out json=results/stress-$(date +%Y%m%d-%H%M%S).json \
            load-test.js
        ;;
    quick)
        echo "Quick Test (10 VUs, 1m)"
        k6 run \
            -e BASE_URL=$BASE_URL \
            --vus 10 \
            --duration 1m \
            --out json=results/quick-$(date +%Y%m%d-%H%M%S).json \
            load-test.js
        ;;
    before)
        echo "Before Measurement (50/100/200 VUs)"
        echo ""
        echo "=== Phase 1: 50 VUs ==="
        k6 run -e BASE_URL=$BASE_URL --vus 50 --duration 2m \
            --out json=results/before-50vus-$(date +%Y%m%d-%H%M%S).json \
            load-test.js

        echo ""
        echo "=== Phase 2: 100 VUs ==="
        k6 run -e BASE_URL=$BASE_URL --vus 100 --duration 2m \
            --out json=results/before-100vus-$(date +%Y%m%d-%H%M%S).json \
            load-test.js

        echo ""
        echo "=== Phase 3: 200 VUs ==="
        k6 run -e BASE_URL=$BASE_URL --vus 200 --duration 2m \
            --out json=results/before-200vus-$(date +%Y%m%d-%H%M%S).json \
            load-test.js
        ;;
    *)
        echo "Usage: ./run-test.sh [smoke|load|stress|quick|before]"
        exit 1
        ;;
esac

echo ""
echo "=========================================="
echo "테스트 완료!"
echo "결과: results/ 폴더 확인"
echo "=========================================="
