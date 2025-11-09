package com.example.shiftv1.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ScheduleGridBulkRequest {

    private List<CreatePayload> create = new ArrayList<>();
    private List<UpdatePayload> update = new ArrayList<>();
    private List<Long> delete = new ArrayList<>();

    public List<CreatePayload> getCreate() {
        return create;
    }

    public void setCreate(List<CreatePayload> create) {
        this.create = create == null ? new ArrayList<>() : new ArrayList<>(create);
    }

    public List<UpdatePayload> getUpdate() {
        return update;
    }

    public void setUpdate(List<UpdatePayload> update) {
        this.update = update == null ? new ArrayList<>() : new ArrayList<>(update);
    }

    public List<Long> getDelete() {
        return delete;
    }

    public void setDelete(List<Long> delete) {
        this.delete = delete == null ? new ArrayList<>() : new ArrayList<>(delete);
    }

    public static class BasePayload {
        private Long employeeId;
        private LocalDate workDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private String shiftName;
        private Boolean isFree;
        private Boolean isOff;
        private Boolean isLeave;

        public Long getEmployeeId() {
            return employeeId;
        }

        public void setEmployeeId(Long employeeId) {
            this.employeeId = employeeId;
        }

        public LocalDate getWorkDate() {
            return workDate;
        }

        public void setWorkDate(LocalDate workDate) {
            this.workDate = workDate;
        }

        public LocalTime getStartTime() {
            return startTime;
        }

        public void setStartTime(LocalTime startTime) {
            this.startTime = startTime;
        }

        public LocalTime getEndTime() {
            return endTime;
        }

        public void setEndTime(LocalTime endTime) {
            this.endTime = endTime;
        }

        public String getShiftName() {
            return shiftName;
        }

        public void setShiftName(String shiftName) {
            this.shiftName = shiftName;
        }

        public Boolean getIsFree() {
            return isFree;
        }

        public void setIsFree(Boolean isFree) {
            this.isFree = isFree;
        }

        public Boolean getIsOff() {
            return isOff;
        }

        public void setIsOff(Boolean isOff) {
            this.isOff = isOff;
        }

        public Boolean getIsLeave() {
            return isLeave;
        }

        public void setIsLeave(Boolean isLeave) {
            this.isLeave = isLeave;
        }
    }

    public static class CreatePayload extends BasePayload {
    }

    public static class UpdatePayload extends BasePayload {
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }
}

