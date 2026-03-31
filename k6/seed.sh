#!/bin/bash
# PetStar 데이터 시딩 스크립트
# 사용법: ./seed.sh [host] [user] [password] [database]

set -e

# RDS 접속 정보
DB_HOST="${1:-petstar-db.cpwka4iukgmy.ap-northeast-2.rds.amazonaws.com}"
DB_USER="${2:-admin}"
DB_PASS="${3:-}"
DB_NAME="${4:-petstar}"

if [ -z "$DB_PASS" ]; then
    echo "Usage: ./seed.sh [host] [user] [password] [database]"
    echo "Example: ./seed.sh petstar-db.xxx.rds.amazonaws.com admin mypassword petstar"
    exit 1
fi

MYSQL_CMD="mysql -h $DB_HOST -u $DB_USER -p$DB_PASS $DB_NAME"

echo "=========================================="
echo "PetStar 데이터 시딩 시작"
echo "Host: $DB_HOST"
echo "Database: $DB_NAME"
echo "=========================================="

# 현재 데이터 수 확인
echo ""
echo "[현재 데이터 현황]"
$MYSQL_CMD -e "
SELECT 'member' as tbl, COUNT(*) as cnt FROM member
UNION ALL SELECT 'pet', COUNT(*) FROM pet
UNION ALL SELECT 'challenge', COUNT(*) FROM challenge
UNION ALL SELECT 'entry', COUNT(*) FROM entry
UNION ALL SELECT 'vote', COUNT(*) FROM vote;
"

# 1. Challenge 시딩 (50건)
echo ""
echo "[1/5] Challenge 시딩 (50건)..."
$MYSQL_CMD < seed-fast.sql
echo "Challenge 완료!"

# 2. Member 시딩 (10,000건)
echo ""
echo "[2/5] Member 시딩 (10,000건)..."
MEMBER_SQL=$(mktemp)
echo "SET FOREIGN_KEY_CHECKS = 0;" > $MEMBER_SQL
echo "SET UNIQUE_CHECKS = 0;" >> $MEMBER_SQL

for i in $(seq 1 100); do
    # 100명씩 배치 INSERT (총 10,000명)
    echo "INSERT INTO member (email, password, nickname, role, created_at, updated_at) VALUES" >> $MEMBER_SQL
    for j in $(seq 1 100); do
        idx=$(( (i-1)*100 + j ))
        if [ $j -eq 100 ]; then
            echo "('user${idx}@petstar.test', '\$2a\$10\$N9qo8uLOickgx2ZMRZoMye', 'TestUser${idx}', 'USER', NOW(), NOW());" >> $MEMBER_SQL
        else
            echo "('user${idx}@petstar.test', '\$2a\$10\$N9qo8uLOickgx2ZMRZoMye', 'TestUser${idx}', 'USER', NOW(), NOW())," >> $MEMBER_SQL
        fi
    done
done

echo "SET FOREIGN_KEY_CHECKS = 1;" >> $MEMBER_SQL
echo "SET UNIQUE_CHECKS = 1;" >> $MEMBER_SQL
echo "COMMIT;" >> $MEMBER_SQL

$MYSQL_CMD < $MEMBER_SQL
rm $MEMBER_SQL
echo "Member 완료!"

# 3. Pet 시딩 (15,000건)
echo ""
echo "[3/5] Pet 시딩 (15,000건)..."
PET_SQL=$(mktemp)
SPECIES=("DOG" "CAT" "BIRD" "FISH" "RABBIT" "HAMSTER" "ETC")
GENDERS=("MALE" "FEMALE" "UNKNOWN")

echo "SET FOREIGN_KEY_CHECKS = 0;" > $PET_SQL

for i in $(seq 1 150); do
    echo "INSERT INTO pet (member_id, name, species, breed, gender, created_at, updated_at) VALUES" >> $PET_SQL
    for j in $(seq 1 100); do
        idx=$(( (i-1)*100 + j ))
        member_id=$(( (idx % 10000) + 1 ))
        species=${SPECIES[$((RANDOM % 7))]}
        gender=${GENDERS[$((RANDOM % 3))]}
        if [ $j -eq 100 ]; then
            echo "(${member_id}, 'Pet${idx}', '${species}', 'Breed$((RANDOM % 50))', '${gender}', NOW(), NOW());" >> $PET_SQL
        else
            echo "(${member_id}, 'Pet${idx}', '${species}', 'Breed$((RANDOM % 50))', '${gender}', NOW(), NOW())," >> $PET_SQL
        fi
    done
done

echo "SET FOREIGN_KEY_CHECKS = 1;" >> $PET_SQL
echo "COMMIT;" >> $PET_SQL

$MYSQL_CMD < $PET_SQL
rm $PET_SQL
echo "Pet 완료!"

# 4. Entry 시딩 (100,000건)
echo ""
echo "[4/5] Entry 시딩 (100,000건)..."
ENTRY_SQL=$(mktemp)

echo "SET FOREIGN_KEY_CHECKS = 0;" > $ENTRY_SQL

for i in $(seq 1 1000); do
    echo "INSERT INTO entry (challenge_id, pet_id, member_id, caption, vote_count, created_at, updated_at) VALUES" >> $ENTRY_SQL
    for j in $(seq 1 100); do
        idx=$(( (i-1)*100 + j ))
        challenge_id=$(( (idx % 30) + 1 ))  # ACTIVE 챌린지 위주 (1~30)
        pet_id=$(( (idx % 15000) + 1 ))
        member_id=$(( (idx % 10000) + 1 ))
        if [ $j -eq 100 ]; then
            echo "(${challenge_id}, ${pet_id}, ${member_id}, 'Entry caption ${idx}', 0, NOW(), NOW());" >> $ENTRY_SQL
        else
            echo "(${challenge_id}, ${pet_id}, ${member_id}, 'Entry caption ${idx}', 0, NOW(), NOW())," >> $ENTRY_SQL
        fi
    done

    if [ $((i % 100)) -eq 0 ]; then
        echo "  Entry progress: $((i * 100)) / 100000"
    fi
done

echo "SET FOREIGN_KEY_CHECKS = 1;" >> $ENTRY_SQL
echo "COMMIT;" >> $ENTRY_SQL

$MYSQL_CMD < $ENTRY_SQL
rm $ENTRY_SQL
echo "Entry 완료!"

# 5. Vote 시딩 (1,000,000건) - 시간이 오래 걸림
echo ""
echo "[5/5] Vote 시딩 (1,000,000건)... (약 10-20분 소요)"
VOTE_SQL=$(mktemp)

echo "SET FOREIGN_KEY_CHECKS = 0;" > $VOTE_SQL
echo "SET UNIQUE_CHECKS = 0;" >> $VOTE_SQL

for i in $(seq 1 10000); do
    echo "INSERT IGNORE INTO vote (entry_id, member_id, created_at) VALUES" >> $VOTE_SQL
    for j in $(seq 1 100); do
        entry_id=$(( (RANDOM % 100000) + 1 ))
        member_id=$(( (RANDOM % 10000) + 1 ))
        if [ $j -eq 100 ]; then
            echo "(${entry_id}, ${member_id}, NOW());" >> $VOTE_SQL
        else
            echo "(${entry_id}, ${member_id}, NOW())," >> $VOTE_SQL
        fi
    done

    if [ $((i % 1000)) -eq 0 ]; then
        echo "  Vote progress: $((i * 100)) / 1000000"
    fi
done

echo "SET FOREIGN_KEY_CHECKS = 1;" >> $VOTE_SQL
echo "SET UNIQUE_CHECKS = 1;" >> $VOTE_SQL
echo "COMMIT;" >> $VOTE_SQL

$MYSQL_CMD < $VOTE_SQL
rm $VOTE_SQL
echo "Vote 완료!"

# 6. vote_count 업데이트
echo ""
echo "[보너스] Entry vote_count 업데이트..."
$MYSQL_CMD -e "UPDATE entry e SET vote_count = (SELECT COUNT(*) FROM vote v WHERE v.entry_id = e.id);"
echo "vote_count 업데이트 완료!"

# 최종 데이터 확인
echo ""
echo "=========================================="
echo "[최종 데이터 현황]"
$MYSQL_CMD -e "
SELECT 'member' as tbl, COUNT(*) as cnt FROM member
UNION ALL SELECT 'pet', COUNT(*) FROM pet
UNION ALL SELECT 'challenge', COUNT(*) FROM challenge
UNION ALL SELECT 'entry', COUNT(*) FROM entry
UNION ALL SELECT 'vote', COUNT(*) FROM vote;
"
echo "=========================================="
echo "시딩 완료!"