package com.exittrading.app.domain;

import jakarta.persistence.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Entity representing a scrip (stock/instrument) configured for market depth logging.
 */
@Entity
@Table(name = "logging_scrips")
public class LoggingScrip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(nullable = false)
    private String exchange;

    @Column(nullable = false)
    private String tradingsymbol;

    private String instrumentToken;

    private boolean active = true;

    private ZonedDateTime addedAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    private ZonedDateTime lastLoggedAt;

    public Long getId() {
        return id;
    }

    public UserAccount getUser() {
        return user;
    }

    public void setUser(UserAccount user) {
        this.user = user;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getTradingsymbol() {
        return tradingsymbol;
    }

    public void setTradingsymbol(String tradingsymbol) {
        this.tradingsymbol = tradingsymbol;
    }

    public String getInstrumentToken() {
        return instrumentToken;
    }

    public void setInstrumentToken(String instrumentToken) {
        this.instrumentToken = instrumentToken;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public ZonedDateTime getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(ZonedDateTime addedAt) {
        this.addedAt = addedAt;
    }

    public ZonedDateTime getLastLoggedAt() {
        return lastLoggedAt;
    }

    public void setLastLoggedAt(ZonedDateTime lastLoggedAt) {
        this.lastLoggedAt = lastLoggedAt;
    }
}
