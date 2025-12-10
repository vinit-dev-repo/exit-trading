package com.exittrading.app.service;

import com.exittrading.app.domain.OrderSide;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.dto.DepthView;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.DepthLevel;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Quote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultKiteGatewayTest {

    private KiteSessionManager sessionManager;
    private DefaultKiteGateway gateway;
    private FixedIstClock clock;
    private InstrumentService instrumentService;

    @BeforeEach
    void setUp() {
        clock = new FixedIstClock(ZonedDateTime.of(2024, 5, 1, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata")));
        sessionManager = new KiteSessionManager(clock);
        instrumentService = new InstrumentService(sessionManager);
        gateway = new DefaultKiteGateway(sessionManager, clock, instrumentService);
        setExchange("NSE");
        KiteConnect.ORDERS.clear();
        KiteConnect.CANCELLED.clear();
        KiteConnect.QUOTES.clear();
        KiteConnect.LTPS.clear();
        KiteConnect.LAST_ORDER_PARAMS = null;
        KiteConnect.LAST_VARIETY = null;
        KiteConnect.orderSequence.set(100);
        KiteConnect.MOCK_USER = null;
        sessionManager.initializeSession(new KiteConnect("test"), "tester", clock.now().plusHours(6));
    }

    @Test
    void shouldPlacePcaOrderUsingKiteConnect() {
        TradingSchedule schedule = new TradingSchedule();
        schedule.setTradingsymbol("INFY");
        schedule.setQuantity(25);
        schedule.setSide(OrderSide.BUY);
        schedule.setLimitPrice(new BigDecimal("1510.25"));

        String orderId = gateway.placePcaOrder(schedule).join();

        assertThat(orderId).startsWith("OID");
        assertThat(KiteConnect.LAST_ORDER_PARAMS).isNotNull();
        assertThat(KiteConnect.LAST_ORDER_PARAMS.tradingsymbol).isEqualTo("INFY");
        assertThat(KiteConnect.LAST_ORDER_PARAMS.quantity).isEqualTo(25);
        assertThat(KiteConnect.LAST_ORDER_PARAMS.transactionType).isEqualTo(Constants.TRANSACTION_TYPE_BUY);
        assertThat(KiteConnect.LAST_ORDER_PARAMS.orderType).isEqualTo(Constants.ORDER_TYPE_LIMIT);
        assertThat(KiteConnect.LAST_ORDER_PARAMS.product).isEqualTo(Constants.PRODUCT_MIS);
        assertThat(KiteConnect.LAST_ORDER_PARAMS.validity).isEqualTo(Constants.VALIDITY_DAY);
        assertThat(KiteConnect.LAST_ORDER_PARAMS.price).isEqualTo(1510.25);
        assertThat(KiteConnect.LAST_VARIETY).isEqualTo(Constants.VARIETY_REGULAR);
    }

    @Test
    void shouldCancelOpenOrdersBeforeExecution() {
        Order openOrder = new Order();
        openOrder.orderId = "OID201";
        openOrder.tradingsymbol = "SBIN";
        openOrder.status = "OPEN";
        openOrder.transactionType = Constants.TRANSACTION_TYPE_BUY;
        openOrder.variety = Constants.VARIETY_REGULAR;
        KiteConnect.ORDERS.add(openOrder);

        Order closedOrder = new Order();
        closedOrder.orderId = "OID202";
        closedOrder.tradingsymbol = "SBIN";
        closedOrder.status = "COMPLETE";
        closedOrder.transactionType = Constants.TRANSACTION_TYPE_BUY;
        closedOrder.variety = Constants.VARIETY_REGULAR;
        KiteConnect.ORDERS.add(closedOrder);

        gateway.cancelOpenOrders("SBIN", OrderSide.BUY).join();

        assertThat(KiteConnect.CANCELLED).containsExactly("OID201:" + Constants.VARIETY_REGULAR);
    }

    @Test
    void shouldComputeDepthFromQuotes() {
        Quote quote = new Quote();
        quote.lastPrice = 245.75;
        quote.depth.buy.add(new DepthLevel(245.60, 100));
        quote.depth.buy.add(new DepthLevel(245.55, 150));
        quote.depth.sell.add(new DepthLevel(245.90, 200));
        KiteConnect.QUOTES.put("NSE:RELIANCE", quote);

        DepthView view = gateway.fetchDepth("RELIANCE", "738561").join();

        assertThat(view.getTradingsymbol()).isEqualTo("RELIANCE");
        assertThat(view.getBuyQuantity()).isEqualTo(250);
        assertThat(view.getSellQuantity()).isEqualTo(200);
        assertThat(view.getLtp()).isEqualTo(BigDecimal.valueOf(245.75));
        assertThat(view.getCapturedAt()).isEqualTo(clock.now());
    }

    private void setExchange(String value) {
        try {
            Field field = DefaultKiteGateway.class.getDeclaredField("exchange");
            field.setAccessible(true);
            field.set(gateway, value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static class FixedIstClock extends IstClock {
        private final ZonedDateTime fixedNow;

        FixedIstClock(ZonedDateTime fixedNow) {
            this.fixedNow = fixedNow;
        }

        @Override
        public ZonedDateTime now() {
            return fixedNow;
        }

        @Override
        public ZonedDateTime fromInstant(Instant instant) {
            return instant.atZone(zoneId());
        }
    }
}
