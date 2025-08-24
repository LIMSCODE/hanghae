package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PointController의 단위 테스트
 * - Mock 객체를 사용하여 database 의존성을 격리
 * - 각 API의 정상/비정상 케이스를 검증
 */
class PointControllerTest {

    @Mock
    private UserPointTable userPointTable;

    @Mock
    private PointHistoryTable pointHistoryTable;

    private PointController pointController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        pointController = new PointController(userPointTable, pointHistoryTable);
    }

    @Test
    @DisplayName("포인트 조회 - 성공: 존재하는 사용자의 포인트 정보를 반환한다")
    void point_success() {
        // Given: 사용자 ID 1번이 1000포인트를 보유한 상황
        long userId = 1L;
        UserPoint expectedPoint = new UserPoint(userId, 1000L, System.currentTimeMillis());
        when(userPointTable.selectById(userId)).thenReturn(expectedPoint);

        // When: 포인트 조회 API를 호출
        UserPoint result = pointController.point(userId);

        // Then: 해당 사용자의 포인트 정보가 반환되어야 한다
        assertThat(result).isEqualTo(expectedPoint);
        verify(userPointTable, times(1)).selectById(userId);
    }

    @Test
    @DisplayName("포인트 조회 - 성공: 존재하지 않는 사용자는 0포인트로 반환한다")
    void point_newUser() {
        // Given: 새로운 사용자 ID 999번 (데이터 없음)
        long userId = 999L;
        UserPoint emptyPoint = UserPoint.empty(userId);
        when(userPointTable.selectById(userId)).thenReturn(emptyPoint);

        // When: 포인트 조회 API를 호출
        UserPoint result = pointController.point(userId);

        // Then: 0포인트를 가진 사용자 정보가 반환되어야 한다
        assertThat(result.id()).isEqualTo(userId);
        assertThat(result.point()).isEqualTo(0L);
    }

    @Test
    @DisplayName("포인트 내역 조회 - 성공: 사용자의 모든 포인트 이용 내역을 반환한다")
    void history_success() {
        // Given: 사용자 ID 1번의 포인트 이용 내역
        long userId = 1L;
        List<PointHistory> expectedHistories = List.of(
            new PointHistory(1L, userId, 1000L, TransactionType.CHARGE, System.currentTimeMillis()),
            new PointHistory(2L, userId, 500L, TransactionType.USE, System.currentTimeMillis())
        );
        when(pointHistoryTable.selectAllByUserId(userId)).thenReturn(expectedHistories);

        // When: 포인트 내역 조회 API를 호출
        List<PointHistory> result = pointController.history(userId);

        // Then: 해당 사용자의 모든 포인트 이용 내역이 반환되어야 한다
        assertThat(result).hasSize(2);
        assertThat(result).isEqualTo(expectedHistories);
        verify(pointHistoryTable, times(1)).selectAllByUserId(userId);
    }

    @Test
    @DisplayName("포인트 내역 조회 - 성공: 내역이 없는 사용자는 빈 리스트를 반환한다")
    void history_empty() {
        // Given: 포인트 이용 내역이 없는 사용자
        long userId = 999L;
        when(pointHistoryTable.selectAllByUserId(userId)).thenReturn(List.of());

        // When: 포인트 내역 조회 API를 호출
        List<PointHistory> result = pointController.history(userId);

        // Then: 빈 리스트가 반환되어야 한다
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("포인트 충전 - 성공: 정상적인 충전 요청시 포인트가 증가하고 이력이 저장된다")
    void charge_success() {
        // Given: 현재 500포인트를 보유한 사용자가 1000포인트를 충전하려는 상황
        long userId = 1L;
        long chargeAmount = 1000L;
        long currentPoints = 500L;
        long expectedPoints = 1500L;
        
        UserPoint currentPoint = new UserPoint(userId, currentPoints, System.currentTimeMillis());
        UserPoint updatedPoint = new UserPoint(userId, expectedPoints, System.currentTimeMillis());
        
        when(userPointTable.selectById(userId)).thenReturn(currentPoint);
        when(userPointTable.insertOrUpdate(eq(userId), eq(expectedPoints))).thenReturn(updatedPoint);

        // When: 포인트 충전 API를 호출
        UserPoint result = pointController.charge(userId, chargeAmount);

        // Then: 포인트가 정상적으로 증가하고 이력이 저장되어야 한다
        assertThat(result.point()).isEqualTo(expectedPoints);
        verify(userPointTable, times(1)).selectById(userId);
        verify(userPointTable, times(1)).insertOrUpdate(userId, expectedPoints);
        verify(pointHistoryTable, times(1)).insert(eq(userId), eq(chargeAmount), eq(TransactionType.CHARGE), anyLong());
    }

    @Test
    @DisplayName("포인트 충전 - 실패: 충전 금액이 0 이하일 경우 예외가 발생한다")
    void charge_invalidAmount() {
        // Given: 잘못된 충전 금액 (0 이하)
        long userId = 1L;
        long invalidAmount = 0L;

        // When & Then: 예외가 발생해야 한다
        assertThatThrownBy(() -> pointController.charge(userId, invalidAmount))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("충전 금액은 0보다 커야 합니다.");

        // 데이터베이스 조작이 일어나지 않아야 한다
        verify(userPointTable, never()).selectById(anyLong());
        verify(userPointTable, never()).insertOrUpdate(anyLong(), anyLong());
        verify(pointHistoryTable, never()).insert(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("포인트 충전 - 실패: 음수 충전 금액일 경우 예외가 발생한다")
    void charge_negativeAmount() {
        // Given: 음수 충전 금액
        long userId = 1L;
        long negativeAmount = -100L;

        // When & Then: 예외가 발생해야 한다
        assertThatThrownBy(() -> pointController.charge(userId, negativeAmount))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("충전 금액은 0보다 커야 합니다.");
    }

    @Test
    @DisplayName("포인트 사용 - 성공: 정상적인 사용 요청시 포인트가 차감되고 이력이 저장된다")
    void use_success() {
        // Given: 현재 1000포인트를 보유한 사용자가 300포인트를 사용하려는 상황
        long userId = 1L;
        long useAmount = 300L;
        long currentPoints = 1000L;
        long expectedPoints = 700L;
        
        UserPoint currentPoint = new UserPoint(userId, currentPoints, System.currentTimeMillis());
        UserPoint updatedPoint = new UserPoint(userId, expectedPoints, System.currentTimeMillis());
        
        when(userPointTable.selectById(userId)).thenReturn(currentPoint);
        when(userPointTable.insertOrUpdate(eq(userId), eq(expectedPoints))).thenReturn(updatedPoint);

        // When: 포인트 사용 API를 호출
        UserPoint result = pointController.use(userId, useAmount);

        // Then: 포인트가 정상적으로 차감되고 이력이 저장되어야 한다
        assertThat(result.point()).isEqualTo(expectedPoints);
        verify(userPointTable, times(1)).selectById(userId);
        verify(userPointTable, times(1)).insertOrUpdate(userId, expectedPoints);
        verify(pointHistoryTable, times(1)).insert(eq(userId), eq(useAmount), eq(TransactionType.USE), anyLong());
    }

    @Test
    @DisplayName("포인트 사용 - 실패: 잔고 부족시 예외가 발생한다")
    void use_insufficientBalance() {
        // Given: 현재 500포인트를 보유한 사용자가 1000포인트를 사용하려는 상황 (잔고 부족)
        long userId = 1L;
        long useAmount = 1000L;
        long currentPoints = 500L;
        
        UserPoint currentPoint = new UserPoint(userId, currentPoints, System.currentTimeMillis());
        when(userPointTable.selectById(userId)).thenReturn(currentPoint);

        // When & Then: 잔고 부족 예외가 발생해야 한다
        assertThatThrownBy(() -> pointController.use(userId, useAmount))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("잔고가 부족합니다. 현재 잔고: " + currentPoints);

        // 포인트 차감과 이력 저장이 일어나지 않아야 한다
        verify(userPointTable, times(1)).selectById(userId);
        verify(userPointTable, never()).insertOrUpdate(anyLong(), anyLong());
        verify(pointHistoryTable, never()).insert(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("포인트 사용 - 실패: 사용 금액이 0 이하일 경우 예외가 발생한다")
    void use_invalidAmount() {
        // Given: 잘못된 사용 금액 (0 이하)
        long userId = 1L;
        long invalidAmount = -50L;

        // When & Then: 예외가 발생해야 한다
        assertThatThrownBy(() -> pointController.use(userId, invalidAmount))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("사용 금액은 0보다 커야 합니다.");

        // 데이터베이스 조작이 일어나지 않아야 한다
        verify(userPointTable, never()).selectById(anyLong());
        verify(userPointTable, never()).insertOrUpdate(anyLong(), anyLong());
        verify(pointHistoryTable, never()).insert(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("포인트 사용 - 경계값 테스트: 보유 포인트와 정확히 같은 금액 사용시 잔고가 0이 된다")
    void use_exactBalance() {
        // Given: 현재 1000포인트를 보유한 사용자가 정확히 1000포인트를 사용하려는 상황
        long userId = 1L;
        long useAmount = 1000L;
        long currentPoints = 1000L;
        long expectedPoints = 0L;
        
        UserPoint currentPoint = new UserPoint(userId, currentPoints, System.currentTimeMillis());
        UserPoint updatedPoint = new UserPoint(userId, expectedPoints, System.currentTimeMillis());
        
        when(userPointTable.selectById(userId)).thenReturn(currentPoint);
        when(userPointTable.insertOrUpdate(eq(userId), eq(expectedPoints))).thenReturn(updatedPoint);

        // When: 포인트 사용 API를 호출
        UserPoint result = pointController.use(userId, useAmount);

        // Then: 잔고가 0이 되어야 한다
        assertThat(result.point()).isEqualTo(0L);
        verify(userPointTable, times(1)).insertOrUpdate(userId, 0L);
    }

    @Test
    @DisplayName("포인트 사용 - 경계값 테스트: 보유 포인트보다 1포인트 많이 사용시 예외가 발생한다")
    void use_overBalance() {
        // Given: 현재 1000포인트를 보유한 사용자가 1001포인트를 사용하려는 상황
        long userId = 1L;
        long useAmount = 1001L;
        long currentPoints = 1000L;
        
        UserPoint currentPoint = new UserPoint(userId, currentPoints, System.currentTimeMillis());
        when(userPointTable.selectById(userId)).thenReturn(currentPoint);

        // When & Then: 잔고 부족 예외가 발생해야 한다
        assertThatThrownBy(() -> pointController.use(userId, useAmount))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("잔고가 부족합니다. 현재 잔고: 1000");
    }
}