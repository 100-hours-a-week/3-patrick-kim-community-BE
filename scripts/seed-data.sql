-- ============================================
-- PetStar 부하 테스트 시딩 데이터
--
-- 목표:
--   Member:    10,000명
--   Pet:       15,000마리 (멤버당 1~2마리)
--   Challenge: 30개 (모두 ACTIVE)
--   Image:     ~115,000개 (Entry용 100K + Pet 15K + 기타)
--   Entry:     ~100,000개 (챌린지당 ~3,333개)
--   Vote:      0 (k6 테스트에서 생성)
--
-- 실행: mysql -h <RDS_HOST> -u admin -p petstar < seed-data.sql
-- 소요 시간: ~3-5분
-- ============================================

SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS = 0;
SET autocommit = 0;
SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO';

-- 기존 데이터 정리
TRUNCATE TABLE vote;
TRUNCATE TABLE support_message;
TRUNCATE TABLE entry;
TRUNCATE TABLE pet;
TRUNCATE TABLE challenge;
TRUNCATE TABLE member;
TRUNCATE TABLE image;

-- ============================================
-- 1. Image (115,100개)
--    - 1~100: Challenge thumbnails + Member profiles (여유분)
--    - 101~15,100: Pet profile images (15,000)
--    - 15,101~115,100: Entry images (100,000)
-- ============================================
-- 숫자 생성 테이블 (임시)
DROP TABLE IF EXISTS seq;
CREATE TABLE seq (n INT PRIMARY KEY);

-- 1~1000 시퀀스
INSERT INTO seq (n) VALUES
(0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

DROP TABLE IF EXISTS seq1k;
CREATE TABLE seq1k AS
SELECT a.n * 100 + b.n * 10 + c.n + 1 AS n
FROM seq a, seq b, seq c;

-- 1~115100 생성용
DROP TABLE IF EXISTS seq_big;
CREATE TABLE seq_big AS
SELECT (a.n - 1) * 1000 + b.n AS n
FROM seq1k a, seq1k b
WHERE (a.n - 1) * 1000 + b.n <= 115100;

INSERT INTO image (image_id, s3key, url, status, created_at, updated_at)
SELECT n,
       CONCAT('petstar/images/', n, '.jpg'),
       CONCAT('https://petstar-images.s3.ap-northeast-2.amazonaws.com/petstar/images/', n, '.jpg'),
       0,
       NOW(),
       NOW()
FROM seq_big;

COMMIT;

-- ============================================
-- 2. Member (10,000명)
-- ============================================
INSERT INTO member (member_id, nickname, email, password, role, image_id, created_at, updated_at)
SELECT n,
       CONCAT('user', n),
       CONCAT('user', n, '@test.com'),
       '$2a$10$dummyhasheddummyhasheddummyhashedpasswordhash.',
       'USER',
       NULL,
       NOW(),
       NOW()
FROM seq_big
WHERE n <= 10000;

COMMIT;

-- ============================================
-- 3. Challenge (30개, 모두 ACTIVE)
-- ============================================
INSERT INTO challenge (challenge_id, title, description, status, start_at, end_at, max_entries, created_at, updated_at)
SELECT n,
       CONCAT('챌린지 #', n, ' - 우리 아이 자랑'),
       CONCAT('제', n, '회 PetStar 포토 챌린지입니다. 우리 반려동물의 가장 멋진 순간을 공유해주세요!'),
       'ACTIVE',
       DATE_SUB(NOW(), INTERVAL 7 DAY),
       DATE_ADD(NOW(), INTERVAL 23 DAY),
       10000,
       DATE_SUB(NOW(), INTERVAL 10 DAY),
       NOW()
FROM seq_big
WHERE n <= 30;

COMMIT;

-- ============================================
-- 4. Pet (15,000마리)
--    - Member 1~5000: 각 2마리
--    - Member 5001~10000: 각 1마리
-- ============================================
-- 첫 번째 펫 (Member 1~10000 → Pet 1~10000)
INSERT INTO pet (pet_id, member_id, name, species, breed, birth_date, gender, profile_image_id, created_at, updated_at)
SELECT n,
       n,
       CONCAT('댕댕이', n),
       ELT(1 + (n % 7), 'DOG', 'CAT', 'BIRD', 'FISH', 'RABBIT', 'HAMSTER', 'ETC'),
       NULL,
       DATE_SUB(CURDATE(), INTERVAL (1 + n % 10) YEAR),
       ELT(1 + (n % 3), 'MALE', 'FEMALE', 'UNKNOWN'),
       100 + n,
       NOW(),
       NOW()
FROM seq_big
WHERE n <= 10000;

-- 두 번째 펫 (Member 1~5000 → Pet 10001~15000)
INSERT INTO pet (pet_id, member_id, name, species, breed, birth_date, gender, profile_image_id, created_at, updated_at)
SELECT 10000 + n,
       n,
       CONCAT('냥냥이', n),
       ELT(1 + ((n + 3) % 7), 'DOG', 'CAT', 'BIRD', 'FISH', 'RABBIT', 'HAMSTER', 'ETC'),
       NULL,
       DATE_SUB(CURDATE(), INTERVAL (1 + n % 8) YEAR),
       ELT(1 + ((n + 1) % 3), 'MALE', 'FEMALE', 'UNKNOWN'),
       10100 + n,
       NOW(),
       NOW()
FROM seq_big
WHERE n <= 5000;

COMMIT;

-- ============================================
-- 5. Entry (~100,000개)
--    30 챌린지 × ~3,333 엔트리
--    UK: (challenge_id, pet_id) — 펫당 챌린지 1개씩
--
--    배분:
--      Pet 1~15000을 챌린지에 순환 배정
--      챌린지 c에는 pet_id = (c-1)*500+1 ~ c*500 범위 + 추가 분배
--
--    단순화: Pet 1~15000 × 챌린지 약 6~7개씩
--      entry_id = (challenge_id - 1) * 3334 + seq
-- ============================================

-- 챌린지 1~30, 각 3,333개 엔트리 = 99,990개
-- Pet 배분: 챌린지 c의 j번째 엔트리 → pet_id = ((c-1)*3334 + j - 1) % 15000 + 1
-- Member: pet의 owner

INSERT INTO entry (entry_id, challenge_id, pet_id, member_id, image_id, caption, vote_count, version, created_at, updated_at)
SELECT
    (c.n - 1) * 3334 + j.n AS entry_id,
    c.n AS challenge_id,
    ((c.n - 1) * 3334 + j.n - 1) % 15000 + 1 AS pet_id,
    -- member_id: pet 1~10000은 같은 member, pet 10001~15000은 member = pet_id - 10000
    CASE
        WHEN ((c.n - 1) * 3334 + j.n - 1) % 15000 + 1 <= 10000
        THEN ((c.n - 1) * 3334 + j.n - 1) % 15000 + 1
        ELSE ((c.n - 1) * 3334 + j.n - 1) % 15000 + 1 - 10000
    END AS member_id,
    15100 + (c.n - 1) * 3334 + j.n AS image_id,
    CONCAT('우리 아이 사진 #', (c.n - 1) * 3334 + j.n),
    0,
    0,
    DATE_SUB(NOW(), INTERVAL (j.n % 7) DAY),
    NOW()
FROM
    (SELECT n FROM seq_big WHERE n <= 30) c,
    (SELECT n FROM seq_big WHERE n <= 3334) j;

COMMIT;

-- ============================================
-- 정리
-- ============================================
DROP TABLE IF EXISTS seq;
DROP TABLE IF EXISTS seq1k;
DROP TABLE IF EXISTS seq_big;

SET FOREIGN_KEY_CHECKS = 1;
SET UNIQUE_CHECKS = 1;
SET autocommit = 1;
SET SQL_MODE=@OLD_SQL_MODE;

-- 확인
SELECT 'Members' AS entity, COUNT(*) AS cnt FROM member
UNION ALL SELECT 'Pets', COUNT(*) FROM pet
UNION ALL SELECT 'Challenges', COUNT(*) FROM challenge
UNION ALL SELECT 'Images', COUNT(*) FROM image
UNION ALL SELECT 'Entries', COUNT(*) FROM entry
UNION ALL SELECT 'Votes', COUNT(*) FROM vote;
