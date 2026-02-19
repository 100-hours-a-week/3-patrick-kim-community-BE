-- PetStar 부하테스트용 데이터 시딩 스크립트
-- 목표: Member 10K, Pet 15K, Challenge 50, Entry 100K, Vote 1M

-- 기존 테스트 데이터 삭제 (선택적)
-- TRUNCATE TABLE vote;
-- TRUNCATE TABLE support_message;
-- TRUNCATE TABLE entry;
-- TRUNCATE TABLE challenge;
-- TRUNCATE TABLE pet;
-- DELETE FROM member WHERE id > 100;

-- ============================================
-- 1. Member 시딩 (10,000건)
-- ============================================
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
            '$2a$10$N9qo8uLOickgx2ZMRZoMye6kHXe3a3FJB6.Vr2.zLsOO0.RGQmN4a', -- password: test1234
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
DELIMITER //
DROP PROCEDURE IF EXISTS seed_pets//
CREATE PROCEDURE seed_pets()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE member_id INT;
    DECLARE species_list VARCHAR(255) DEFAULT 'DOG,CAT,BIRD,FISH,RABBIT,HAMSTER,ETC';
    DECLARE gender_list VARCHAR(255) DEFAULT 'MALE,FEMALE,UNKNOWN';
    DECLARE species_val VARCHAR(20);
    DECLARE gender_val VARCHAR(20);

    WHILE i <= 15000 DO
        -- 랜덤 member_id (1 ~ 10000)
        SET member_id = FLOOR(1 + RAND() * 10000);

        -- 랜덤 species
        SET species_val = ELT(FLOOR(1 + RAND() * 7), 'DOG', 'CAT', 'BIRD', 'FISH', 'RABBIT', 'HAMSTER', 'ETC');

        -- 랜덤 gender
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
DELIMITER //
DROP PROCEDURE IF EXISTS seed_challenges//
CREATE PROCEDURE seed_challenges()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE status_val VARCHAR(20);
    DECLARE start_date DATETIME;
    DECLARE end_date DATETIME;

    WHILE i <= 50 DO
        -- 챌린지 상태 분배: 10개 UPCOMING, 30개 ACTIVE, 10개 ENDED
        IF i <= 10 THEN
            SET status_val = 'UPCOMING';
            SET start_date = DATE_ADD(NOW(), INTERVAL (i * 7) DAY);
            SET end_date = DATE_ADD(start_date, INTERVAL 30 DAY);
        ELSEIF i <= 40 THEN
            SET status_val = 'ACTIVE';
            SET start_date = DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 15) DAY);
            SET end_date = DATE_ADD(NOW(), INTERVAL FLOOR(15 + RAND() * 15) DAY);
        ELSE
            SET status_val = 'ENDED';
            SET start_date = DATE_SUB(NOW(), INTERVAL (60 + (i - 40) * 30) DAY);
            SET end_date = DATE_SUB(NOW(), INTERVAL ((i - 40) * 5) DAY);
        END IF;

        INSERT INTO challenge (title, description, status, start_at, end_at, max_entries, created_at, updated_at)
        VALUES (
            CONCAT('Challenge #', i, ': ', ELT(FLOOR(1 + RAND() * 5), '귀여운 강아지', '멋진 고양이', '자는 모습', '산책 중', '간식 먹방'), ' 챌린지'),
            CONCAT('챌린지 #', i, ' 설명입니다. 가장 ', ELT(FLOOR(1 + RAND() * 3), '귀여운', '멋진', '재미있는'), ' 사진을 올려주세요!'),
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
DELIMITER //
DROP PROCEDURE IF EXISTS seed_entries//
CREATE PROCEDURE seed_entries()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE challenge_id INT;
    DECLARE pet_id INT;
    DECLARE member_id INT;
    DECLARE batch_size INT DEFAULT 5000;

    -- Entry 테이블에 vote_count 컬럼이 없다면 추가
    -- ALTER TABLE entry ADD COLUMN IF NOT EXISTS vote_count INT DEFAULT 0;

    WHILE i <= 100000 DO
        -- 챌린지는 ACTIVE (11~40) 위주로 분배
        SET challenge_id = FLOOR(11 + RAND() * 30);

        -- 랜덤 pet_id (1 ~ 15000)
        SET pet_id = FLOOR(1 + RAND() * 15000);

        -- pet의 member_id 조회 (간단히 랜덤으로)
        SET member_id = FLOOR(1 + RAND() * 10000);

        INSERT INTO entry (challenge_id, pet_id, member_id, caption, vote_count, created_at, updated_at)
        VALUES (
            challenge_id,
            pet_id,
            member_id,
            CONCAT('우리 ', ELT(FLOOR(1 + RAND() * 5), '귀여운', '멋진', '사랑스러운', '똑똑한', '장난꾸러기'), ' 펫이에요! #챌린지', challenge_id),
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
DELIMITER //
DROP PROCEDURE IF EXISTS seed_votes//
CREATE PROCEDURE seed_votes()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE entry_id INT;
    DECLARE member_id INT;
    DECLARE batch_size INT DEFAULT 10000;

    -- 인덱스 비활성화 (성능 향상)
    -- ALTER TABLE vote DISABLE KEYS;

    WHILE i <= 1000000 DO
        -- 랜덤 entry_id (1 ~ 100000)
        SET entry_id = FLOOR(1 + RAND() * 100000);

        -- 랜덤 member_id (1 ~ 10000)
        SET member_id = FLOOR(1 + RAND() * 10000);

        -- 중복 투표 방지를 위해 INSERT IGNORE 사용
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

    -- 인덱스 활성화
    -- ALTER TABLE vote ENABLE KEYS;

    -- vote_count 일괄 업데이트
    SELECT 'Updating vote counts...' AS progress;
    UPDATE entry e
    SET vote_count = (SELECT COUNT(*) FROM vote v WHERE v.entry_id = e.id);

    SELECT 'Vote seeding completed!' AS progress;
END//
DELIMITER ;

-- ============================================
-- 실행 순서
-- ============================================
-- CALL seed_members();
-- CALL seed_pets();
-- CALL seed_challenges();
-- CALL seed_entries();
-- CALL seed_votes();

-- ============================================
-- 데이터 확인 쿼리
-- ============================================
-- SELECT 'member' as tbl, COUNT(*) as cnt FROM member
-- UNION ALL SELECT 'pet', COUNT(*) FROM pet
-- UNION ALL SELECT 'challenge', COUNT(*) FROM challenge
-- UNION ALL SELECT 'entry', COUNT(*) FROM entry
-- UNION ALL SELECT 'vote', COUNT(*) FROM vote;