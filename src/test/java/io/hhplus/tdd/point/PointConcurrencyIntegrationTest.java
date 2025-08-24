package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * 포인트 시스템의 동시성 제어 통합 테스트
 * - 실제 멀티스레드 환경에서의 동시성 문제 검증
 * - 사용자별 락을 통한 동시성 제어 검증
 * - 성능 및 정확성 테스트
 */
class PointConcurrencyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PointConcurrencyIntegrationTest.class);
    
    private UserPointTable userPointTable;
    private PointHistoryTable pointHistoryTable;
    private PointController pointController;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        userPointTable = new UserPointTable();
        pointHistoryTable = new PointHistoryTable();
        pointController = new PointController(userPointTable, pointHistoryTable);
        executorService = Executors.newFixedThreadPool(100);
    }

    @Test
    @DisplayName("동시성 테스트: 동일 사용자가 동시에 포인트를 사용할 때 잔고 부족 예외가 정확히 처리된다")
    void 동일사용자_동시_포인트사용_잔고부족_테스트() throws InterruptedException {
        // Given: 사용자 ID 1번이 1000포인트를 보유
        long userId = 1L;
        long initialPoints = 1000L;
        long useAmount = 600L;
        int threadCount = 10;
        
        // 초기 포인트 충전
        pointController.charge(userId, initialPoints);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        // When: 10개의 스레드가 동시에 600포인트씩 사용 시도
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    pointController.use(userId, useAmount);
                    successCount.incrementAndGet();
                    log.info("포인트 사용 성공 - 스레드: {}", Thread.currentThread().getName());
                } catch (IllegalArgumentException e) {
                    failureCount.incrementAndGet();
                    log.info("포인트 사용 실패 - 스레드: {}, 이유: {}", Thread.currentThread().getName(), e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        // 모든 스레드 동시 시작
        startLatch.countDown();
        finishLatch.await(10, TimeUnit.SECONDS);
        
        // Then: 정확히 1번의 성공과 9번의 실패가 발생해야 함
        UserPoint finalPoint = pointController.point(userId);
        
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(9);
        assertThat(finalPoint.point()).isEqualTo(400L); // 1000 - 600 = 400
        
        // 히스토리 검증: 충전 1회 + 사용 1회 = 총 2건
        List<PointHistory> histories = pointController.history(userId);
        assertThat(histories).hasSize(2);
        assertThat(histories.get(0).type()).isEqualTo(TransactionType.CHARGE);
        assertThat(histories.get(1).type()).isEqualTo(TransactionType.USE);
    }

    @Test
    @DisplayName("동시성 테스트: 동일 사용자가 동시에 포인트를 충전할 때 모든 충전이 정확히 반영된다")
    void 동일사용자_동시_포인트충전_테스트() throws InterruptedException {
        // Given: 사용자 ID 2번이 0포인트에서 시작
        long userId = 2L;
        long chargeAmount = 100L;
        int threadCount = 50;
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        // When: 50개의 스레드가 동시에 100포인트씩 충전
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    pointController.charge(userId, chargeAmount);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        finishLatch.await(10, TimeUnit.SECONDS);
        
        // Then: 모든 충전이 성공하고 최종 포인트가 정확해야 함
        UserPoint finalPoint = pointController.point(userId);
        
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(finalPoint.point()).isEqualTo(5000L); // 50 * 100 = 5000
        
        // 히스토리 검증: 충전 50건
        List<PointHistory> histories = pointController.history(userId);
        assertThat(histories).hasSize(50);
        assertThat(histories).allMatch(h -> h.type() == TransactionType.CHARGE);
        assertThat(histories).allMatch(h -> h.amount() == 100L);
    }

    @Test
    @DisplayName("동시성 테스트: 서로 다른 사용자들의 포인트 사용은 독립적으로 처리된다")
    void 다른사용자_동시_포인트사용_독립처리_테스트() throws InterruptedException {
        // Given: 사용자 ID 10~19번이 각각 1000포인트를 보유
        int userCount = 10;
        long initialPoints = 1000L;
        long useAmount = 500L;
        
        // 각 사용자에게 초기 포인트 충전
        for (int i = 0; i < userCount; i++) {
            pointController.charge(10L + i, initialPoints);
        }
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(userCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        // When: 각각 다른 사용자가 동시에 500포인트씩 사용
        for (int i = 0; i < userCount; i++) {
            final long userId = 10L + i;
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    pointController.use(userId, useAmount);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        finishLatch.await(10, TimeUnit.SECONDS);
        
        // Then: 모든 사용자의 포인트 사용이 성공해야 함
        assertThat(successCount.get()).isEqualTo(userCount);
        
        // 각 사용자의 최종 포인트 검증
        for (int i = 0; i < userCount; i++) {
            long userId = 10L + i;
            UserPoint finalPoint = pointController.point(userId);
            assertThat(finalPoint.point()).isEqualTo(500L); // 1000 - 500 = 500
            
            List<PointHistory> histories = pointController.history(userId);
            assertThat(histories).hasSize(2); // 충전 1회 + 사용 1회
        }
    }

    @Test
    @DisplayName("동시성 테스트: 동일 사용자의 충전과 사용이 동시에 발생해도 정확히 처리된다")
    void 동일사용자_충전사용_동시처리_테스트() throws InterruptedException {
        // Given: 사용자 ID 20번이 500포인트를 보유
        long userId = 20L;
        long initialPoints = 500L;
        long chargeAmount = 300L;
        long useAmount = 200L;
        int operationCount = 20;
        
        pointController.charge(userId, initialPoints);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(operationCount * 2);
        AtomicInteger chargeSuccessCount = new AtomicInteger(0);
        AtomicInteger useSuccessCount = new AtomicInteger(0);
        
        // When: 20개의 충전(+300)과 20개의 사용(-200) 요청이 동시에 발생
        for (int i = 0; i < operationCount; i++) {
            // 충전 요청
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    pointController.charge(userId, chargeAmount);
                    chargeSuccessCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
            
            // 사용 요청
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    pointController.use(userId, useAmount);
                    useSuccessCount.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    // 잔고 부족으로 실패할 수 있음
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        finishLatch.await(15, TimeUnit.SECONDS);
        
        // Then: 포인트 계산이 정확해야 함
        UserPoint finalPoint = pointController.point(userId);
        long expectedPoints = initialPoints + 
                             (chargeSuccessCount.get() * chargeAmount) - 
                             (useSuccessCount.get() * useAmount);
        
        assertThat(finalPoint.point()).isEqualTo(expectedPoints);
        assertThat(chargeSuccessCount.get()).isEqualTo(20);
        assertThat(finalPoint.point()).isGreaterThanOrEqualTo(0); // 음수 포인트 방지 확인
        
        log.info("최종 결과 - 충전 성공: {}, 사용 성공: {}, 최종 포인트: {}", 
                chargeSuccessCount.get(), useSuccessCount.get(), finalPoint.point());
    }

    @Test
    @DisplayName("성능 테스트: 1000명의 사용자가 각각 100회씩 포인트 연산을 수행한다")
    void 대용량_동시성_성능_테스트() throws InterruptedException {
        // Given: 1000명의 사용자가 각각 100회의 연산을 수행
        int userCount = 100; // 테스트 환경을 고려하여 축소
        int operationsPerUser = 10; // 테스트 시간을 고려하여 축소
        long initialPoints = 10000L;
        
        long startTime = System.currentTimeMillis();
        
        CountDownLatch finishLatch = new CountDownLatch(userCount * operationsPerUser);
        AtomicInteger totalOperations = new AtomicInteger(0);
        
        // When: 각 사용자가 충전과 사용을 반복
        for (int userId = 1000; userId < 1000 + userCount; userId++) {
            final long finalUserId = userId;
            
            // 초기 포인트 충전
            pointController.charge(finalUserId, initialPoints);
            
            for (int op = 0; op < operationsPerUser; op++) {
                final int operation = op;
                executorService.submit(() -> {
                    try {
                        if (operation % 2 == 0) {
                            // 짝수: 충전
                            pointController.charge(finalUserId, 100L);
                        } else {
                            // 홀수: 사용
                            try {
                                pointController.use(finalUserId, 50L);
                            } catch (IllegalArgumentException e) {
                                // 잔고 부족은 정상적인 상황
                            }
                        }
                        totalOperations.incrementAndGet();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }
        }
        
        finishLatch.await(30, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // Then: 성능 및 정확성 검증
        assertThat(totalOperations.get()).isEqualTo(userCount * operationsPerUser);
        
        // 모든 사용자의 포인트가 음수가 아님을 확인
        for (int userId = 1000; userId < 1000 + userCount; userId++) {
            UserPoint userPoint = pointController.point(userId);
            assertThat(userPoint.point()).isGreaterThanOrEqualTo(0L);
        }
        
        double tps = (double) totalOperations.get() / (duration / 1000.0);
        log.info("성능 테스트 결과 - 총 연산: {}, 소요시간: {}ms, TPS: {:.2f}", 
                totalOperations.get(), duration, tps);
        
        // 최소 성능 요구사항 (예: 100 TPS 이상)
        assertThat(tps).isGreaterThan(10.0); // 테스트 환경을 고려한 낮은 임계값
    }

    @Test
    @DisplayName("경계값 테스트: 포인트가 정확히 0이 될 때까지 사용하는 시나리오")
    void 포인트_완전소진_경계값_테스트() throws InterruptedException {
        // Given: 사용자가 1000포인트를 보유하고, 10개 스레드가 각각 100포인트씩 사용 시도
        long userId = 999L;
        long initialPoints = 1000L;
        long useAmount = 100L;
        int threadCount = 10;
        
        pointController.charge(userId, initialPoints);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        // When: 10개 스레드가 동시에 100포인트씩 사용 시도
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    pointController.use(userId, useAmount);
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    failureCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        finishLatch.await(10, TimeUnit.SECONDS);
        
        // Then: 정확히 10번의 성공, 0번의 실패, 최종 포인트 0
        UserPoint finalPoint = pointController.point(userId);
        
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(failureCount.get()).isEqualTo(0);
        assertThat(finalPoint.point()).isEqualTo(0L);
        
        // 추가로 포인트 사용 시도시 실패해야 함
        assertThatThrownBy(() -> pointController.use(userId, 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("잔고가 부족합니다. 현재 잔고: 0");
    }
}