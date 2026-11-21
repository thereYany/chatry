package com.wherelee.base.modules.platform.alert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

@Repository
public class AlertLogRepository {

    private final JdbcTemplate jdbc;

    public AlertLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String alertType, String severity, String message,
                       boolean delivered, String deliveryError) {
        jdbc.update("""
                INSERT INTO t_alert_log (alert_type, severity, message, delivered, delivery_error)
                VALUES (?, ?, ?, ?, ?)
                """, alertType, severity, message, delivered, deliveryError);
    }
}