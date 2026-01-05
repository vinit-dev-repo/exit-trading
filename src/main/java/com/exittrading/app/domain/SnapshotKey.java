package com.exittrading.app.domain;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.Objects;

public class SnapshotKey implements Serializable {
    private ZonedDateTime capturedAt;
    private Long token;

    public SnapshotKey() {}

    public SnapshotKey(ZonedDateTime capturedAt, Long token) {
        this.capturedAt = capturedAt;
        this.token = token;
    }

    public ZonedDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(ZonedDateTime capturedAt) { this.capturedAt = capturedAt; }

    public Long getToken() { return token; }
    public void setToken(Long token) { this.token = token; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SnapshotKey that = (SnapshotKey) o;
        return Objects.equals(capturedAt, that.capturedAt) && Objects.equals(token, that.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(capturedAt, token);
    }
}
