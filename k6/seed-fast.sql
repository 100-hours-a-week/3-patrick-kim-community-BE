-- PetStar 빠른 시딩 스크립트 (배치 INSERT)
-- 실행 전: SET GLOBAL max_allowed_packet=1073741824;

SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS = 0;
SET AUTOCOMMIT = 0;

-- ============================================
-- 1. Challenge 시딩 (50건) - 먼저 실행
-- ============================================
INSERT INTO challenge (title, description, status, start_at, end_at, max_entries, created_at, updated_at) VALUES
('귀여운 강아지 챌린지 #1', '가장 귀여운 강아지 사진을 올려주세요!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_ADD(NOW(), INTERVAL 25 DAY), 5000, NOW(), NOW()),
('멋진 고양이 챌린지 #2', '가장 멋진 고양이 사진을 올려주세요!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_ADD(NOW(), INTERVAL 20 DAY), 5000, NOW(), NOW()),
('자는 모습 챌린지 #3', '펫의 자는 모습을 올려주세요!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_ADD(NOW(), INTERVAL 27 DAY), 5000, NOW(), NOW()),
('산책 중 챌린지 #4', '산책하는 펫 사진을 올려주세요!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_ADD(NOW(), INTERVAL 23 DAY), 5000, NOW(), NOW()),
('간식 먹방 챌린지 #5', '간식 먹는 모습을 올려주세요!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 29 DAY), 5000, NOW(), NOW()),
('목욕 시간 챌린지 #6', '목욕하는 펫 사진!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_ADD(NOW(), INTERVAL 22 DAY), 5000, NOW(), NOW()),
('장난감 놀이 챌린지 #7', '장난감 가지고 노는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_ADD(NOW(), INTERVAL 26 DAY), 5000, NOW(), NOW()),
('옷 입은 챌린지 #8', '옷 입은 펫 사진!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_ADD(NOW(), INTERVAL 24 DAY), 5000, NOW(), NOW()),
('친구와 함께 챌린지 #9', '다른 펫과 함께!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_ADD(NOW(), INTERVAL 21 DAY), 5000, NOW(), NOW()),
('주인과 셀카 챌린지 #10', '주인과 함께 찍은 셀카!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_ADD(NOW(), INTERVAL 28 DAY), 5000, NOW(), NOW()),
('졸린 눈 챌린지 #11', '졸린 눈 사진!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 11 DAY), DATE_ADD(NOW(), INTERVAL 19 DAY), 5000, NOW(), NOW()),
('혀 내민 챌린지 #12', '혀 내민 사진!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 12 DAY), DATE_ADD(NOW(), INTERVAL 18 DAY), 5000, NOW(), NOW()),
('귀 쫑긋 챌린지 #13', '귀 쫑긋한 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_ADD(NOW(), INTERVAL 25 DAY), 5000, NOW(), NOW()),
('간식 기다림 챌린지 #14', '간식 기다리는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_ADD(NOW(), INTERVAL 24 DAY), 5000, NOW(), NOW()),
('꿀잠 챌린지 #15', '꿀잠 자는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_ADD(NOW(), INTERVAL 27 DAY), 5000, NOW(), NOW()),
('달리기 챌린지 #16', '달리는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_ADD(NOW(), INTERVAL 22 DAY), 5000, NOW(), NOW()),
('점프 챌린지 #17', '점프하는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_ADD(NOW(), INTERVAL 26 DAY), 5000, NOW(), NOW()),
('장난꾸러기 챌린지 #18', '장난치는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_ADD(NOW(), INTERVAL 23 DAY), 5000, NOW(), NOW()),
('앉아 챌린지 #19', '앉아있는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_ADD(NOW(), INTERVAL 21 DAY), 5000, NOW(), NOW()),
('누워 챌린지 #20', '누워있는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_ADD(NOW(), INTERVAL 20 DAY), 5000, NOW(), NOW()),
('배 보여줘 챌린지 #21', '배 보여주는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 29 DAY), 5000, NOW(), NOW()),
('고개 갸웃 챌린지 #22', '고개 갸웃하는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_ADD(NOW(), INTERVAL 28 DAY), 5000, NOW(), NOW()),
('밥 먹기 챌린지 #23', '밥 먹는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 11 DAY), DATE_ADD(NOW(), INTERVAL 19 DAY), 5000, NOW(), NOW()),
('물 마시기 챌린지 #24', '물 마시는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 12 DAY), DATE_ADD(NOW(), INTERVAL 18 DAY), 5000, NOW(), NOW()),
('하품 챌린지 #25', '하품하는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_ADD(NOW(), INTERVAL 25 DAY), 5000, NOW(), NOW()),
('기지개 챌린지 #26', '기지개 펴는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_ADD(NOW(), INTERVAL 24 DAY), 5000, NOW(), NOW()),
('숨바꼭질 챌린지 #27', '숨어있는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_ADD(NOW(), INTERVAL 27 DAY), 5000, NOW(), NOW()),
('창밖 구경 챌린지 #28', '창밖 보는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_ADD(NOW(), INTERVAL 22 DAY), 5000, NOW(), NOW()),
('햇살 아래 챌린지 #29', '햇살 아래 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_ADD(NOW(), INTERVAL 26 DAY), 5000, NOW(), NOW()),
('베개 사용 챌린지 #30', '베개 사용하는 모습!', 'ACTIVE', DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_ADD(NOW(), INTERVAL 23 DAY), 5000, NOW(), NOW()),
('예정 챌린지 #31', '곧 시작될 챌린지!', 'UPCOMING', DATE_ADD(NOW(), INTERVAL 7 DAY), DATE_ADD(NOW(), INTERVAL 37 DAY), 5000, NOW(), NOW()),
('예정 챌린지 #32', '곧 시작될 챌린지!', 'UPCOMING', DATE_ADD(NOW(), INTERVAL 14 DAY), DATE_ADD(NOW(), INTERVAL 44 DAY), 5000, NOW(), NOW()),
('예정 챌린지 #33', '곧 시작될 챌린지!', 'UPCOMING', DATE_ADD(NOW(), INTERVAL 21 DAY), DATE_ADD(NOW(), INTERVAL 51 DAY), 5000, NOW(), NOW()),
('예정 챌린지 #34', '곧 시작될 챌린지!', 'UPCOMING', DATE_ADD(NOW(), INTERVAL 28 DAY), DATE_ADD(NOW(), INTERVAL 58 DAY), 5000, NOW(), NOW()),
('예정 챌린지 #35', '곧 시작될 챌린지!', 'UPCOMING', DATE_ADD(NOW(), INTERVAL 35 DAY), DATE_ADD(NOW(), INTERVAL 65 DAY), 5000, NOW(), NOW()),
('예정 챌린지 #36', '곧 시작될 챌린지!', 'UPCOMING', DATE_ADD(NOW(), INTERVAL 42 DAY), DATE_ADD(NOW(), INTERVAL 72 DAY), 5000, NOW(), NOW()),
('예정 챌린지 #37', '곧 시작될 챌린지!', 'UPCOMING', DATE_ADD(NOW(), INTERVAL 49 DAY), DATE_ADD(NOW(), INTERVAL 79 DAY), 5000, NOW(), NOW()),
('예정 챌린지 #38', '곧 시작될 챌린지!', 'UPCOMING', DATE_ADD(NOW(), INTERVAL 56 DAY), DATE_ADD(NOW(), INTERVAL 86 DAY), 5000, NOW(), NOW()),
('예정 챌린지 #39', '곧 시작될 챌린지!', 'UPCOMING', DATE_ADD(NOW(), INTERVAL 63 DAY), DATE_ADD(NOW(), INTERVAL 93 DAY), 5000, NOW(), NOW()),
('예정 챌린지 #40', '곧 시작될 챌린지!', 'UPCOMING', DATE_ADD(NOW(), INTERVAL 70 DAY), DATE_ADD(NOW(), INTERVAL 100 DAY), 5000, NOW(), NOW()),
('종료 챌린지 #41', '종료된 챌린지', 'ENDED', DATE_SUB(NOW(), INTERVAL 60 DAY), DATE_SUB(NOW(), INTERVAL 30 DAY), 5000, NOW(), NOW()),
('종료 챌린지 #42', '종료된 챌린지', 'ENDED', DATE_SUB(NOW(), INTERVAL 90 DAY), DATE_SUB(NOW(), INTERVAL 60 DAY), 5000, NOW(), NOW()),
('종료 챌린지 #43', '종료된 챌린지', 'ENDED', DATE_SUB(NOW(), INTERVAL 120 DAY), DATE_SUB(NOW(), INTERVAL 90 DAY), 5000, NOW(), NOW()),
('종료 챌린지 #44', '종료된 챌린지', 'ENDED', DATE_SUB(NOW(), INTERVAL 150 DAY), DATE_SUB(NOW(), INTERVAL 120 DAY), 5000, NOW(), NOW()),
('종료 챌린지 #45', '종료된 챌린지', 'ENDED', DATE_SUB(NOW(), INTERVAL 180 DAY), DATE_SUB(NOW(), INTERVAL 150 DAY), 5000, NOW(), NOW()),
('종료 챌린지 #46', '종료된 챌린지', 'ENDED', DATE_SUB(NOW(), INTERVAL 210 DAY), DATE_SUB(NOW(), INTERVAL 180 DAY), 5000, NOW(), NOW()),
('종료 챌린지 #47', '종료된 챌린지', 'ENDED', DATE_SUB(NOW(), INTERVAL 240 DAY), DATE_SUB(NOW(), INTERVAL 210 DAY), 5000, NOW(), NOW()),
('종료 챌린지 #48', '종료된 챌린지', 'ENDED', DATE_SUB(NOW(), INTERVAL 270 DAY), DATE_SUB(NOW(), INTERVAL 240 DAY), 5000, NOW(), NOW()),
('종료 챌린지 #49', '종료된 챌린지', 'ENDED', DATE_SUB(NOW(), INTERVAL 300 DAY), DATE_SUB(NOW(), INTERVAL 270 DAY), 5000, NOW(), NOW()),
('종료 챌린지 #50', '종료된 챌린지', 'ENDED', DATE_SUB(NOW(), INTERVAL 330 DAY), DATE_SUB(NOW(), INTERVAL 300 DAY), 5000, NOW(), NOW());

COMMIT;

-- ============================================
-- 2. Member 대량 시딩 - Python/Shell로 생성 권장
-- ============================================
-- 아래 쉘 스크립트로 대량 INSERT 생성:
-- for i in $(seq 1 10000); do
--   echo "INSERT INTO member (email, password, nickname, role, created_at, updated_at) VALUES ('user$i@petstar.test', '\$2a\$10\$N9qo8uLOickgx2ZMRZoMye6kHXe3a3FJB6.Vr2.zLsOO0.RGQmN4a', 'TestUser$i', 'USER', NOW(), NOW());"
-- done > members.sql

-- ============================================
-- 데이터 확인
-- ============================================
SELECT 'challenge' as tbl, COUNT(*) as cnt FROM challenge;

SET FOREIGN_KEY_CHECKS = 1;
SET UNIQUE_CHECKS = 1;
SET AUTOCOMMIT = 1;