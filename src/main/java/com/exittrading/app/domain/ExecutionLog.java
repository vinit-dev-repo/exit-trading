package com.exittrading.app.domain;

import jakarta.persistence.*;

import java.time.ZonedDateTime;

@Entity
@Table(name = "execution_logs")
public class ExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private TradingSchedule schedule;

    @Column(nullable = false)
    private ZonedDateTime timestamp;

    @Column(nullable = false)
    private String level;

    @Column(nullable = false, length = 2000)
    private String message;

    private String detail;

    public ExecutionLog() {
    }

    public ExecutionLog(TradingSchedule schedule, String level, String message, String detail, ZonedDateTime timestamp) {
        this.schedule = schedule;
        this.level = level;
        this.message = message;
        this.detail = detail;
        this.timestamp = timestamp;
    }

    public Long getId() {
        return id;
    }

    public TradingSchedule getSchedule() {
        return schedule;
    }

    public void setSchedule(TradingSchedule schedule) {
        this.schedule = schedule;
    }

    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(ZonedDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
