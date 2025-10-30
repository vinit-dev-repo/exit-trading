package com.exittrading.app.service;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.DepthLevel;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.Quote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KiteSamplesCompatibilityTest {

    @BeforeEach
    void cleanState() {
        KiteConnect.QUOTES.clear();
        KiteConnect.ORDERS.clear();
        KiteConnect.CANCELLED.clear();
        KiteConnect.LAST_ORDER_PARAMS = null;
        KiteConnect.orderSequence.set(200);
    }

    @Test
    void placeOrderSampleShouldReturnOrderId() throws Exception {
        KiteConnect kiteConnect = new KiteConnect("sample");
        OrderParams orderParams = new OrderParams();
        orderParams.quantity = 1;
        orderParams.orderType = Constants.ORDER_TYPE_LIMIT;
        orderParams.tradingsymbol = "ASHOKLEY";
        orderParams.product = Constants.PRODUCT_CNC;
        orderParams.exchange = Constants.EXCHANGE_NSE;
        orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
        orderParams.validity = Constants.VALIDITY_DAY;
        orderParams.price = 122.2;
        orderParams.triggerPrice = 0.0;
        orderParams.tag = "myTag";

        Map<String, String> order = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);

        assertThat(order.get("order_id")).isEqualTo("OID" + KiteConnect.orderSequence.get());
        assertThat(KiteConnect.LAST_ORDER_PARAMS.tradingsymbol).isEqualTo("ASHOKLEY");
    }

    @Test
    void getQuoteSampleShouldExposeDepthAndPrice() throws Exception {
        KiteConnect kiteConnect = new KiteConnect("sample");
        Quote quote = new Quote();
        quote.lastPrice = 456.10;
        quote.depth.buy.add(new DepthLevel(456.00, 50));
        quote.depth.sell.add(new DepthLevel(456.50, 60));
        KiteConnect.QUOTES.put("NSE:APOLLOTYRE", quote);

        Map<String, Quote> quotes = kiteConnect.getQuote(new String[]{"NSE:APOLLOTYRE"});

        assertThat(quotes).containsKey("NSE:APOLLOTYRE");
        Quote received = quotes.get("NSE:APOLLOTYRE");
        assertThat(received.depth.buy).hasSize(1);
        assertThat(received.lastPrice).isEqualTo(456.10);
    }
}
