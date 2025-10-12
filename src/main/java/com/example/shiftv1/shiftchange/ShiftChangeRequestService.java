package com.example.shiftv1.shiftchange;

import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import com.example.shiftv1.schedule.ShiftAssignment;
import com.example.shiftv1.schedule.ShiftAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class ShiftChangeRequestService {

    private static final Logger logger = LoggerFactory.getLogger(ShiftChangeRequestService.class);

    private final ShiftChangeRequestRepository changeRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final ShiftAssignmentRepository shiftAssignmentRepository;

    public ShiftChangeRequestService(ShiftChangeRequestRepository changeRequestRepository,
                                   EmployeeRepository employeeRepository,
                                   ShiftAssignmentRepository shiftAssignmentRepository) {
        this.changeRequestRepository = changeRequestRepository;
        this.employeeRepository = employeeRepository;
        this.shiftAssignmentRepository = shiftAssignmentRepository;
    }

    /**
     * シフト変更申請を作成（代行者指定なし）
     */
    public ShiftChangeRequest createChangeRequest(Long originalShiftId, Long requesterId, String reason) {
        ShiftAssignment originalShift = shiftAssignmentRepository.findById(originalShiftId)
                .orElseThrow(() -> new IllegalArgumentException("シフトが見つかりません: " + originalShiftId));

        Employee requester = employeeRepository.findById(requesterId)
                .orElseThrow(() -> new IllegalArgumentException("従業員が見つかりません: " + requesterId));

        // 申請者がシフトの担当者かチェック
        if (!originalShift.getEmployee().getId().equals(requesterId)) {
            throw new IllegalArgumentException("申請者はこのシフトの担当者ではありません");
        }

        // 既存の申請をチェック
        List<ShiftChangeRequest> existingRequests = changeRequestRepository
                .findByOriginalShiftAndActiveTrueOrderByRequestedAtDesc(originalShift);
        for (ShiftChangeRequest existing : existingRequests) {
            if (existing.getStatus() == ShiftChangeRequest.ShiftChangeStatus.PENDING) {
                throw new IllegalStateException("このシフトには既に申請中の変更申請があります");
            }
        }

        ShiftChangeRequest request = new ShiftChangeRequest(originalShift, requester, reason);
        ShiftChangeRequest saved = changeRequestRepository.save(request);

        logger.info("シフト変更申請を作成しました: ID={}, シフト={}, 申請者={}", 
                   saved.getId(), originalShift.getWorkDate(), requester.getName());

        return saved;
    }

    /**
     * シフト変更申請を作成（代行者指定あり）
     */
    public ShiftChangeRequest createChangeRequestWithSubstitute(Long originalShiftId, Long requesterId, 
                                                              Long substituteId, String reason) {
        ShiftAssignment originalShift = shiftAssignmentRepository.findById(originalShiftId)
                .orElseThrow(() -> new IllegalArgumentException("シフトが見つかりません: " + originalShiftId));

        Employee requester = employeeRepository.findById(requesterId)
                .orElseThrow(() -> new IllegalArgumentException("申請者が見つかりません: " + requesterId));

        Employee substitute = employeeRepository.findById(substituteId)
                .orElseThrow(() -> new IllegalArgumentException("代行者が見つかりません: " + substituteId));

        // 申請者がシフトの担当者かチェック
        if (!originalShift.getEmployee().getId().equals(requesterId)) {
            throw new IllegalArgumentException("申請者はこのシフトの担当者ではありません");
        }

        // 代行者が申請者と異なるかチェック
        if (requesterId.equals(substituteId)) {
            throw new IllegalArgumentException("申請者と代行者は異なる必要があります");
        }

        // 代行者の勤務可能性をチェック（簡易版）
        if (!isSubstituteAvailable(substitute, originalShift.getWorkDate(), 
                                 originalShift.getStartTime(), originalShift.getEndTime())) {
            throw new IllegalStateException("代行者は指定日時に勤務できません");
        }

        ShiftChangeRequest request = new ShiftChangeRequest(originalShift, requester, substitute, reason);
        ShiftChangeRequest saved = changeRequestRepository.save(request);

        logger.info("代行者指定のシフト変更申請を作成しました: ID={}, シフト={}, 申請者={}, 代行者={}", 
                   saved.getId(), originalShift.getWorkDate(), requester.getName(), substitute.getName());

        return saved;
    }

    /**
     * 変更申請を承認
     */
    public ShiftChangeRequest approveRequest(Long requestId, Long adminId, String adminComment) {
        ShiftChangeRequest request = changeRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("変更申請が見つかりません: " + requestId));

        Employee admin = employeeRepository.findById(adminId)
                .orElseThrow(() -> new IllegalArgumentException("管理者が見つかりません: " + adminId));

        if (request.getStatus() != ShiftChangeRequest.ShiftChangeStatus.PENDING) {
            throw new IllegalStateException("承認可能な状態ではありません");
        }

        // 代行者が指定されている場合、シフトを変更
        if (request.getSubstitute() != null) {
            ShiftAssignment originalShift = request.getOriginalShift();
            originalShift.setEmployee(request.getSubstitute());
            shiftAssignmentRepository.save(originalShift);

            logger.info("シフトを変更しました: 日付={}, 元担当者={}, 新担当者={}", 
                       originalShift.getWorkDate(), request.getRequester().getName(), 
                       request.getSubstitute().getName());
        }

        request.setStatus(ShiftChangeRequest.ShiftChangeStatus.APPROVED);
        request.setProcessedAt(LocalDateTime.now());
        request.setProcessedBy(admin);
        request.setAdminComment(adminComment);

        ShiftChangeRequest saved = changeRequestRepository.save(request);

        logger.info("変更申請を承認しました: ID={}, 処理者={}", requestId, admin.getName());

        return saved;
    }

    /**
     * 変更申請を却下
     */
    public ShiftChangeRequest rejectRequest(Long requestId, Long adminId, String adminComment) {
        ShiftChangeRequest request = changeRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("変更申請が見つかりません: " + requestId));

        Employee admin = employeeRepository.findById(adminId)
                .orElseThrow(() -> new IllegalArgumentException("管理者が見つかりません: " + adminId));

        if (request.getStatus() != ShiftChangeRequest.ShiftChangeStatus.PENDING) {
            throw new IllegalStateException("却下可能な状態ではありません");
        }

        request.setStatus(ShiftChangeRequest.ShiftChangeStatus.REJECTED);
        request.setProcessedAt(LocalDateTime.now());
        request.setProcessedBy(admin);
        request.setAdminComment(adminComment);

        ShiftChangeRequest saved = changeRequestRepository.save(request);

        logger.info("変更申請を却下しました: ID={}, 処理者={}", requestId, admin.getName());

        return saved;
    }

    /**
     * 変更申請をキャンセル
     */
    public ShiftChangeRequest cancelRequest(Long requestId, Long requesterId) {
        ShiftChangeRequest request = changeRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("変更申請が見つかりません: " + requestId));

        if (!request.getRequester().getId().equals(requesterId)) {
            throw new IllegalArgumentException("申請者のみがキャンセルできます");
        }

        if (request.getStatus() != ShiftChangeRequest.ShiftChangeStatus.PENDING) {
            throw new IllegalStateException("キャンセル可能な状態ではありません");
        }

        request.setStatus(ShiftChangeRequest.ShiftChangeStatus.CANCELLED);
        request.setProcessedAt(LocalDateTime.now());

        ShiftChangeRequest saved = changeRequestRepository.save(request);

        logger.info("変更申請をキャンセルしました: ID={}, 申請者={}", requestId, request.getRequester().getName());

        return saved;
    }

    /**
     * 申請者の変更申請一覧を取得
     */
    @Transactional(readOnly = true)
    public List<ShiftChangeRequest> getRequestsByRequester(Long employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("従業員が見つかりません: " + employeeId));

        return changeRequestRepository.findByRequesterAndActiveTrueOrderByRequestedAtDesc(employee);
    }

    /**
     * 代行者の変更申請一覧を取得
     */
    @Transactional(readOnly = true)
    public List<ShiftChangeRequest> getRequestsBySubstitute(Long employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("従業員が見つかりません: " + employeeId));

        return changeRequestRepository.findBySubstituteAndActiveTrueOrderByRequestedAtDesc(employee);
    }

    /**
     * 承認待ちの変更申請一覧を取得
     */
    @Transactional(readOnly = true)
    public List<ShiftChangeRequest> getPendingRequests() {
        return changeRequestRepository.findPendingRequestsOrderByRequestedAt();
    }

    /**
     * 変更申請詳細を取得
     */
    @Transactional(readOnly = true)
    public Optional<ShiftChangeRequest> getRequest(Long requestId) {
        return changeRequestRepository.findById(requestId);
    }

    /**
     * 変更申請統計を取得
     */
    @Transactional(readOnly = true)
    public List<Object[]> getRequestStatistics(LocalDateTime startDate, LocalDateTime endDate) {
        return changeRequestRepository.getRequestStatisticsByDateRange(startDate, endDate);
    }

    /**
     * 代行者の勤務可能性をチェック（簡易版）
     */
    private boolean isSubstituteAvailable(Employee substitute, java.time.LocalDate workDate, 
                                        LocalTime shiftStartTime, LocalTime shiftEndTime) {
        // 簡易的な実装：同じ日付に他のシフトがないかチェック
        List<ShiftAssignment> existingShifts = shiftAssignmentRepository
                .findByEmployeeAndWorkDate(substitute, workDate);
        
        for (ShiftAssignment existingShift : existingShifts) {
            if (shiftsOverlap(shiftStartTime, shiftEndTime, 
                            existingShift.getStartTime(), existingShift.getEndTime())) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * シフト時間の重複チェック
     */
    private boolean shiftsOverlap(LocalTime start1, LocalTime end1, LocalTime start2, LocalTime end2) {
        return start1.isBefore(end2) && end1.isAfter(start2);
    }
}
