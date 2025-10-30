package com.exittrading.app.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

@Entity
@Table(name = "depth_snapshots")
public class DepthSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(nullable = false)
    private String tradingsymbol;

    private long buyQuantity;

    private long sellQuantity;

    private BigDecimal ltp;

    private ZonedDateTime capturedAt;

    public Long getId() {
        return id;
    }

    public UserAccount getUser() {
        return user;
    }

    public void setUser(UserAccount user) {
        this.user = user;
    }

    public String getTradingsymbol() {
        return tradingsymbol;
    }

    public void setTradingsymbol(String tradingsymbol) {
        this.tradingsymbol = tradingsymbol;
    }

    public long getBuyQuantity() {
        return buyQuantity;
    }

    public void setBuyQuantity(long buyQuantity) {
        this.buyQuantity = buyQuantity;
    }

    public long getSellQuantity() {
        return sellQuantity;
    }

    public void setSellQuantity(long sellQuantity) {
        this.sellQuantity = sellQuantity;
    }

    public BigDecimal getLtp() {
        return ltp;
    }

    public void setLtp(BigDecimal ltp) {
        this.ltp = ltp;
    }

    public ZonedDateTime getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(ZonedDateTime capturedAt) {
        this.capturedAt = capturedAt;
    }
}
