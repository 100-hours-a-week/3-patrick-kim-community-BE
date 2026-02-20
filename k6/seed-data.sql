-- ============================================
-- PetStar 부하테스트용 데이터 시딩 스크립트
-- ============================================
--
-- 목표 데이터 규모:
--   - Member: 10,000건 (초기 서비스 사용자 규모)
--   - Pet: 15,000건 (사용자당 평균 1.5마리)
--   - Challenge: 50건 (UPCOMING 10, ACTIVE 30, ENDED 10)
--   - Entry: 100,000건 (챌린지당 평균 2,000개)
--   - Vote: 1,000,000건 (Entry당 평균 10표)
--
-- 왜 이 규모인가?
--   - 개발 환경(100건)에서는 문제가 안 보임
--   - 실제 운영 환경 규모에서 병목 발생
--   - 10만 Entry로 ORDER BY 정렬 비용 확인
--   - 100만 Vote로 집계 쿼리 성능 확인
--
-- 실행 방법:
--   mysql -h [RDS_ENDPOINT] -u admin -p petstar < seed-data.sql
--   또는
--   CALL seed_members();
--   CALL seed_pets();
--   CALL seed_challenges();
--   CALL seed_entries();
--   CALL seed_votes();

-- ============================================
-- 1. Member 시딩 (10,000건)
-- ============================================
-- 가정: 초기 서비스 사용자 규모
-- 특징: 
--   - 테스트용 이메일 (user1@petstar.test ~ user10000@petstar.test)
--   - 동일한 해시 비밀번호 (test1234)
--   - 모두 일반 사용자 (USER 역할)

DELIMITER //
DROP PROCEDURE IF EXISTS seed_members//
CREATE PROCEDURE seed_members()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE batch_size INT DEFAULT 1000;

    WHILE i <= 10000 DO
        INSERT INTO member (email, password, nickname, role, created_at, updated_at)
        VALUES (
            CONCAT('user', i, '@petstar.test'),
            '$2a$10$N9qo8uLOickgx2ZMRZoMye6kHXe3a3FJB6.Vr2.zLsOO0.RGQmN4a',
            CONCAT('TestUser', i),
            'USER',
            NOW(),
            NOW()
        );

        IF i % batch_size = 0 THEN
            SELECT CONCAT('Members created: ', i) AS progress;
        END IF;

        SET i = i + 1;
    END WHILE;
END//
DELIMITER ;

-- ============================================
-- 2. Pet 시딩 (15,000건)
-- ============================================
-- 가정: 사용자당 평균 1.5마리 반려동물
-- 특징:
--   - 7종 species 랜덤 분배 (DOG, CAT, BIRD, FISH, RABBIT, HAMSTER, ETC)
--   - 3종 gender 랜덤 분배 (MALE, FEMALE, UNKNOWN)
--   - 랜덤 member_id에 할당

DELIMITER //
DROP PROCEDURE IF EXISTS seed_pets//
CREATE PROCEDURE seed_pets()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE member_id INT;
    DECLARE species_val VARCHAR(20);
    DECLARE gender_val VARCHAR(20);

    WHILE i <= 15000 DO
        SET member_id = FLOOR(1 + RAND() * 10000);
        SET species_val = ELT(FLOOR(1 + RAND() * 7), 'DOG', 'CAT', 'BIRD', 'FISH', 'RABBIT', 'HAMSTER', 'ETC');
        SET gender_val = ELT(FLOOR(1 + RAND() * 3), 'MALE', 'FEMALE', 'UNKNOWN');

        INSERT INTO pet (member_id, name, species, breed, birth_date, gender, created_at, updated_at)
        VALUES (
            member_id,
            CONCAT('Pet', i),
            species_val,
            CONCAT('Breed', FLOOR(1 + RAND() * 50)),
            DATE_SUB(CURDATE(), INTERVAL FLOOR(RAND() * 3650) DAY),
            gender_val,
            NOW(),
            NOW()
        );

        IF i % 1000 = 0 THEN
            SELECT CONCAT('Pets created: ', i) AS progress;
        END IF;

        SET i = i + 1;
    END WHILE;
END//
DELIMITER ;

-- ============================================
-- 3. Challenge 시딩 (50건)
-- ============================================
-- 가정: 서비스에서 진행 중인 다양한 챌린지
-- 분배:
--   - UPCOMING (1~10): 예정된 챌린지
--   - ACTIVE (11~40): 진행 중인 챌린지 (테스트 메인 타겟)
--   - ENDED (41~50): 종료된 챌린지

DELIMITER //
DROP PROCEDURE IF EXISTS seed_challenges//
CREATE PROCEDURE seed_challenges()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE status_val VARCHAR(20);
    DECLARE start_date DATETIME;
    DECLARE end_date DATETIME;
    DECLARE title_prefix VARCHAR(50);

    WHILE i <= 50 DO
        -- 챌린지 상태 분배
        IF i <= 10 THEN
            SET status_val = 'UPCOMING';
            SET start_date = DATE_ADD(NOW(), INTERVAL (i * 7) DAY);
            SET end_date = DATE_ADD(start_date, INTERVAL 30 DAY);
            SET title_prefix = '예정 챌린지';
        ELSEIF i <= 40 THEN
            SET status_val = 'ACTIVE';
            SET start_date = DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 15) DAY);
            SET end_date = DATE_ADD(NOW(), INTERVAL FLOOR(15 + RAND() * 15) DAY);
            SET title_prefix = ELT(FLOOR(1 + RAND() * 5), 
                '귀여운 포즈 챌린지', '산책 인증 챌린지', '간식 타임 챌린지', 
                '숨바꼭질 챌린지', '창밖 구경 챌린지');
        ELSE
            SET status_val = 'ENDED';
            SET start_date = DATE_SUB(NOW(), INTERVAL (60 + (i - 40) * 30) DAY);
            SET end_date = DATE_SUB(NOW(), INTERVAL ((i - 40) * 5) DAY);
            SET title_prefix = '종료된 챌린지';
        END IF;

        INSERT INTO challenge (title, description, status, start_at, end_at, max_entries, created_at, updated_at)
        VALUES (
            CONCAT(title_prefix, ' #', i),
            CONCAT('챌린지 #', i, ' 설명입니다. 가장 멋진 사진을 올려주세요!'),
            status_val,
            start_date,
            end_date,
            FLOOR(1000 + RAND() * 9000),
            NOW(),
            NOW()
        );

        SET i = i + 1;
    END WHILE;

    SELECT 'Challenges created: 50' AS progress;
END//
DELIMITER ;

-- ============================================
-- 4. Entry 시딩 (100,000건)
-- ============================================
-- 가정: 챌린지당 평균 2,000개 출품
-- 특징:
--   - ACTIVE 챌린지(11~40)에 집중 분배
--   - 초기 vote_count = 0 (Vote 시딩 후 업데이트)
-- 
-- 왜 10만건?
--   - SELECT ... ORDER BY vote_count DESC LIMIT 10
--   - 10만건 정렬 → 인덱스 없으면 filesort 발생
--   - 이 시점에서 인덱스 필요성 명확해짐

DELIMITER //
DROP PROCEDURE IF EXISTS seed_entries//
CREATE PROCEDURE seed_entries()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE challenge_id INT;
    DECLARE pet_id INT;
    DECLARE member_id INT;
    DECLARE batch_size INT DEFAULT 5000;

    WHILE i <= 100000 DO
        -- ACTIVE 챌린지 (11~40)에 주로 분배
        SET challenge_id = FLOOR(11 + RAND() * 30);
        SET pet_id = FLOOR(1 + RAND() * 15000);
        SET member_id = FLOOR(1 + RAND() * 10000);

        INSERT INTO entry (challenge_id, pet_id, member_id, caption, vote_count, created_at, updated_at)
        VALUES (
            challenge_id,
            pet_id,
            member_id,
            CONCAT('우리 펫이에요! #챌린지', challenge_id),
            0,
            DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 30) DAY),
            NOW()
        );

        IF i % batch_size = 0 THEN
            SELECT CONCAT('Entries created: ', i) AS progress;
        END IF;

        SET i = i + 1;
    END WHILE;
END//
DELIMITER ;

-- ============================================
-- 5. Vote 시딩 (1,000,000건) + vote_count 업데이트
-- ============================================
-- 가정: Entry당 평균 10표
-- 특징:
--   - INSERT IGNORE로 중복 투표 방지
--   - 시딩 완료 후 vote_count 일괄 업데이트
--
-- 왜 100만건?
--   - 투표 집계 쿼리 성능 확인
--   - Hot Entry에 투표 집중 시뮬레이션

DELIMITER //
DROP PROCEDURE IF EXISTS seed_votes//
CREATE PROCEDURE seed_votes()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE entry_id INT;
    DECLARE member_id INT;
    DECLARE batch_size INT DEFAULT 10000;

    WHILE i <= 1000000 DO
        SET entry_id = FLOOR(1 + RAND() * 100000);
        SET member_id = FLOOR(1 + RAND() * 10000);

        INSERT IGNORE INTO vote (entry_id, member_id, created_at)
        VALUES (
            entry_id,
            member_id,
            DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 30) DAY)
        );

        IF i % batch_size = 0 THEN
            SELECT CONCAT('Votes created: ', i) AS progress;
        END IF;

        SET i = i + 1;
    END WHILE;

    -- vote_count 일괄 업데이트
    SELECT 'Updating vote counts...' AS progress;
    UPDATE entry e
    SET vote_count = (SELECT COUNT(*) FROM vote v WHERE v.entry_id = e.id);

    SELECT 'Vote seeding completed!' AS progress;
END//
DELIMITER ;

-- ============================================
-- 실행 순서 (순서대로 실행)
-- ============================================
-- CALL seed_members();    -- 약 1분
-- CALL seed_pets();       -- 약 1분
-- CALL seed_challenges(); -- 즉시
-- CALL seed_entries();    -- 약 5분
-- CALL seed_votes();      -- 약 20분

-- ============================================
-- 데이터 확인 쿼리
-- ============================================
-- SELECT 'member' as tbl, COUNT(*) as cnt FROM member
-- UNION ALL SELECT 'pet', COUNT(*) FROM pet
-- UNION ALL SELECT 'challenge', COUNT(*) FROM challenge
-- UNION ALL SELECT 'entry', COUNT(*) FROM entry
-- UNION ALL SELECT 'vote', COUNT(*) FROM vote;
