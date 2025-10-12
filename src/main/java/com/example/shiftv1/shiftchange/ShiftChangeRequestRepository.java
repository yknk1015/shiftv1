package com.example.shiftv1.shiftchange;

import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.schedule.ShiftAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ShiftChangeRequestRepository extends JpaRepository<ShiftChangeRequest, Long> {

    /**
     * 申請者の変更申請一覧を取得
     */
    List<ShiftChangeRequest> findByRequesterAndActiveTrueOrderByRequestedAtDesc(Employee requester);

    /**
     * 代行者の変更申請一覧を取得
     */
    List<ShiftChangeRequest> findBySubstituteAndActiveTrueOrderByRequestedAtDesc(Employee substitute);

    /**
     * 指定されたシフトの変更申請一覧を取得
     */
    List<ShiftChangeRequest> findByOriginalShiftAndActiveTrueOrderByRequestedAtDesc(ShiftAssignment originalShift);

    /**
     * ステータス別の変更申請一覧を取得
     */
    List<ShiftChangeRequest> findByStatusAndActiveTrueOrderByRequestedAtDesc(ShiftChangeRequest.ShiftChangeStatus status);

    /**
     * 承認待ちの変更申請一覧を取得
     */
    @Query("SELECT scr FROM ShiftChangeRequest scr WHERE scr.status = 'PENDING' AND scr.active = true " +
           "ORDER BY scr.requestedAt ASC")
    List<ShiftChangeRequest> findPendingRequestsOrderByRequestedAt();

    /**
     * 指定期間の変更申請一覧を取得
     */
    @Query("SELECT scr FROM ShiftChangeRequest scr WHERE scr.requestedAt BETWEEN :startDate AND :endDate " +
           "AND scr.active = true ORDER BY scr.requestedAt DESC")
    List<ShiftChangeRequest> findByRequestedAtBetweenAndActiveTrue(
            @Param("startDate") LocalDateTime startDate, 
            @Param("endDate") LocalDateTime endDate);

    /**
     * 指定従業員の承認済み変更申請一覧を取得
     */
    @Query("SELECT scr FROM ShiftChangeRequest scr WHERE (scr.requester = :employee OR scr.substitute = :employee) " +
           "AND scr.status = 'APPROVED' AND scr.active = true ORDER BY scr.requestedAt DESC")
    List<ShiftChangeRequest> findApprovedRequestsByEmployee(@Param("employee") Employee employee);

    /**
     * 指定従業員の変更申請数を取得
     */
    long countByRequesterAndActiveTrue(Employee requester);

    /**
     * 指定従業員の代行申請数を取得
     */
    long countBySubstituteAndActiveTrue(Employee substitute);

    /**
     * ステータス別の変更申請数を取得
     */
    long countByStatusAndActiveTrue(ShiftChangeRequest.ShiftChangeStatus status);

    /**
     * 指定期間の変更申請統計を取得
     */
    @Query("SELECT scr.status, COUNT(scr) FROM ShiftChangeRequest scr " +
           "WHERE scr.requestedAt BETWEEN :startDate AND :endDate AND scr.active = true " +
           "GROUP BY scr.status")
    List<Object[]> getRequestStatisticsByDateRange(
            @Param("startDate") LocalDateTime startDate, 
            @Param("endDate") LocalDateTime endDate);

    /**
     * 指定従業員の月間変更申請数を取得
     */
    @Query("SELECT COUNT(scr) FROM ShiftChangeRequest scr WHERE scr.requester = :employee " +
           "AND YEAR(scr.requestedAt) = :year AND MONTH(scr.requestedAt) = :month " +
           "AND scr.active = true")
    long countMonthlyRequestsByRequester(
            @Param("employee") Employee employee, 
            @Param("year") int year, 
            @Param("month") int month);

    /**
     * 指定従業員の月間代行申請数を取得
     */
    @Query("SELECT COUNT(scr) FROM ShiftChangeRequest scr WHERE scr.substitute = :employee " +
           "AND YEAR(scr.requestedAt) = :year AND MONTH(scr.requestedAt) = :month " +
           "AND scr.active = true")
    long countMonthlySubstitutionsBySubstitute(
            @Param("employee") Employee employee, 
            @Param("year") int year, 
            @Param("month") int month);

    /**
     * 最近の変更申請を取得（管理画面用）
     */
    @Query("SELECT scr FROM ShiftChangeRequest scr WHERE scr.active = true " +
           "ORDER BY scr.requestedAt DESC")
    List<ShiftChangeRequest> findRecentRequests(@Param("limit") int limit);

    /**
     * 指定従業員の未処理の変更申請を取得
     */
    @Query("SELECT scr FROM ShiftChangeRequest scr WHERE (scr.requester = :employee OR scr.substitute = :employee) " +
           "AND scr.status = 'PENDING' AND scr.active = true ORDER BY scr.requestedAt ASC")
    List<ShiftChangeRequest> findPendingRequestsByEmployee(@Param("employee") Employee employee);
}
