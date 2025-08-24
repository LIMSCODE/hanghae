package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/point")
public class PointController {

    private static final Logger log = LoggerFactory.getLogger(PointController.class);
    private final UserPointTable userPointTable;
    private final PointHistoryTable pointHistoryTable;

    public PointController(UserPointTable userPointTable, PointHistoryTable pointHistoryTable) {
        this.userPointTable = userPointTable;
        this.pointHistoryTable = pointHistoryTable;
    }

    /**
     * TODO - 특정 유저의 포인트를 조회하는 기능을 작성해주세요.
     */
    @GetMapping("{id}")
    public UserPoint point(
            @PathVariable long id
    ) {
        log.info("포인트 조회 요청 - 사용자 ID: {}", id);
        UserPoint result = userPointTable.selectById(id);
        log.info("포인트 조회 결과 - ID: {}, 포인트: {}, 업데이트시간: {}", result.id(), result.point(), result.updateMillis());
        return result;
    }

    /**
     * TODO - 특정 유저의 포인트 충전/이용 내역을 조회하는 기능을 작성해주세요.
     */
    @GetMapping("{id}/histories")
    public List<PointHistory> history(
            @PathVariable long id
    ) {
        log.info("포인트 내역 조회 요청 - 사용자 ID: {}", id);
        List<PointHistory> histories = pointHistoryTable.selectAllByUserId(id);
        log.info("포인트 내역 조회 결과 - 사용자 ID: {}, 내역 개수: {}", id, histories.size());
        return histories;
    }

    /**
     * TODO - 특정 유저의 포인트를 충전하는 기능을 작성해주세요.
     */
    @PatchMapping("{id}/charge")
    public UserPoint charge(
            @PathVariable long id,
            @RequestBody long amount
    ) {
        log.info("포인트 충전 요청 - 사용자 ID: {}, 충전금액: {}", id, amount);
        
        if (amount <= 0) {
            log.error("포인트 충전 실패 - 잘못된 금액: {}", amount);
            throw new IllegalArgumentException("충전 금액은 0보다 커야 합니다.");
        }

        UserPoint currentPoint = userPointTable.selectById(id);
        log.info("현재 포인트 조회 - ID: {}, 현재포인트: {}", id, currentPoint.point());
        
        long newAmount = currentPoint.point() + amount;
        log.info("포인트 충전 계산 - 현재: {} + 충전: {} = 새포인트: {}", currentPoint.point(), amount, newAmount);
        
        UserPoint updatedPoint = userPointTable.insertOrUpdate(id, newAmount);
        pointHistoryTable.insert(id, amount, TransactionType.CHARGE, updatedPoint.updateMillis());
        
        log.info("포인트 충전 완료 - 사용자 ID: {}, 최종포인트: {}, 업데이트시간: {}", 
                updatedPoint.id(), updatedPoint.point(), updatedPoint.updateMillis());
        
        return updatedPoint;
    }

    /**
     * TODO - 특정 유저의 포인트를 사용하는 기능을 작성해주세요.
     */
    @PatchMapping("{id}/use")
    public UserPoint use(
            @PathVariable long id,
            @RequestBody long amount
    ) {
        log.info("포인트 사용 요청 - 사용자 ID: {}, 사용금액: {}", id, amount);
        
        if (amount <= 0) {
            log.error("포인트 사용 실패 - 잘못된 금액: {}", amount);
            throw new IllegalArgumentException("사용 금액은 0보다 커야 합니다.");
        }

        UserPoint currentPoint = userPointTable.selectById(id);
        log.info("현재 포인트 조회 - ID: {}, 현재포인트: {}", id, currentPoint.point());
        
        if (currentPoint.point() < amount) {
            log.error("포인트 사용 실패 - 잔고부족. 현재잔고: {}, 사용요청: {}", currentPoint.point(), amount);
            throw new IllegalArgumentException("잔고가 부족합니다. 현재 잔고: " + currentPoint.point());
        }
        
        long newAmount = currentPoint.point() - amount;
        log.info("포인트 사용 계산 - 현재: {} - 사용: {} = 새포인트: {}", currentPoint.point(), amount, newAmount);
        
        UserPoint updatedPoint = userPointTable.insertOrUpdate(id, newAmount);
        pointHistoryTable.insert(id, amount, TransactionType.USE, updatedPoint.updateMillis());
        
        log.info("포인트 사용 완료 - 사용자 ID: {}, 최종포인트: {}, 업데이트시간: {}", 
                updatedPoint.id(), updatedPoint.point(), updatedPoint.updateMillis());
        
        return updatedPoint;
    }
}
