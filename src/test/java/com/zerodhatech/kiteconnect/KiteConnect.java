package com.zerodhatech.kiteconnect;

import com.zerodhatech.kiteconnect.kitehttp.SessionExpiryHook;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.*;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class KiteConnect {
    private final String apiKey;
    private String userId;
    private String accessToken;
    private String publicToken;
    private SessionExpiryHook sessionExpiryHook;

    public static final AtomicInteger orderSequence = new AtomicInteger(100);
    public static final List<Order> ORDERS = new ArrayList<>();
    public static final Map<String, Quote> QUOTES = new HashMap<>();
    public static final Map<String, Quote> LTPS = new HashMap<>();
    public static final List<String> CANCELLED = new ArrayList<>();
    public static OrderParams LAST_ORDER_PARAMS;
    public static String LAST_VARIETY;
    public static User MOCK_USER;

    private final Map<String, Quote> ohlc = new HashMap<>();
    private final Map<String, List<Trade>> tradesByOrder = new HashMap<>();
    private final Map<String, GTT> gtts = new HashMap<>();
    private final Map<String, MFOrder> mfOrders = new HashMap<>();
    private final Map<String, MFSIP> mfSips = new HashMap<>();

    public KiteConnect(String apiKey) {
        this.apiKey = apiKey;
        seedDefaults();
    }

    private void seedDefaults() {
        if (ORDERS.isEmpty()) {
            Order order = new Order();
            order.orderId = "OID101";
            order.tradingsymbol = "INFY";
            order.orderType = "LIMIT";
            order.averagePrice = 1500.5;
            order.exchangeTimestamp = "2024-05-10T10:00:00";
            order.exchangeUpdateTimestamp = "2024-05-10T10:00:01";
            order.guid = "GUID1";
            ORDERS.add(order);
        }
        if (QUOTES.isEmpty()) {
            Quote apollo = new Quote();
            apollo.instrumentToken = "256265";
            apollo.lastPrice = 456.10;
            apollo.oi = 12345;
            apollo.timestamp = "2024-05-10T10:05:00";
            apollo.lowerCircuitLimit = 400.0;
            apollo.upperCircuitLimit = 500.0;
            apollo.oiDayHigh = 15000;
            apollo.oiDayLow = 10000;
            apollo.ohlc.open = 450.0;
            apollo.depth.buy.add(new DepthLevel(455.90, 100));
            apollo.depth.buy.add(new DepthLevel(455.80, 200));
            apollo.depth.buy.add(new DepthLevel(455.70, 300));
            apollo.depth.buy.add(new DepthLevel(455.60, 400));
            apollo.depth.buy.add(new DepthLevel(455.50, 500));
            apollo.depth.sell.add(new DepthLevel(456.20, 100));

            Quote nifty = new Quote();
            nifty.instrumentToken = "NIFTY";
            nifty.lastPrice = 22000.0;
            nifty.ohlc.open = 21950.0;

            Quote generic = new Quote();
            generic.instrumentToken = "24507906";
            generic.lastPrice = 100.0;
            generic.oiDayHigh = 5000;
            generic.oiDayLow = 4500;

            QUOTES.put("NSE:APOLLOTYRE", apollo);
            QUOTES.put("256265", apollo);
            QUOTES.put("24507906", generic);
            QUOTES.put("NSE:NIFTY 50", nifty);
            QUOTES.put("NSE:INFY", apollo);
            LTPS.putAll(QUOTES);
        }
        if (ohlc.isEmpty()) {
            ohlc.putAll(QUOTES);
        }
        if (tradesByOrder.isEmpty()) {
            Trade trade = new Trade();
            trade.tradingSymbol = "INFY";
            tradesByOrder.put("OID101", Collections.singletonList(trade));
        }
        if (gtts.isEmpty()) {
            GTT gtt = new GTT();
            gtt.id = 177574;
            gtt.createdAt = "2024-05-10T10:00:00";
            gtt.condition.exchange = "NSE";
            gtt.condition.tradingSymbol = "SBIN";
            gtts.put(String.valueOf(gtt.id), gtt);

            GTT cancelTarget = new GTT();
            cancelTarget.id = 175859;
            cancelTarget.condition.exchange = "NSE";
            cancelTarget.condition.tradingSymbol = "SBIN";
            gtts.put(String.valueOf(cancelTarget.id), cancelTarget);
        }
        if (mfOrders.isEmpty()) {
            MFOrder order = new MFOrder();
            order.orderId = "106580291331583";
            order.tradingsymbol = "INF174K01LS2";
            mfOrders.put(order.orderId, order);
        }
        if (mfSips.isEmpty()) {
            MFSIP sip = new MFSIP();
            sip.sipId = "291156521960679";
            sip.instalments = 12;
            mfSips.put(sip.sipId, sip);

            MFSIP activeSip = new MFSIP();
            activeSip.sipId = "504341441825418";
            activeSip.instalments = 6;
            mfSips.put(activeSip.sipId, activeSip);
        }
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getLoginURL() {
        return "https://kite.trade/connect/login?api_key=" + apiKey;
    }

    public User generateSession(String requestToken, String apiSecret) {
        if (MOCK_USER == null) {
            User user = new User();
            user.userName = "stub-user";
            user.accessToken = "access";
            user.publicToken = "public";
            user.accessTokenExpiry = new java.util.Date(System.currentTimeMillis() + 3_600_000);
            return user;
        }
        return MOCK_USER;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public void setPublicToken(String publicToken) {
        this.publicToken = publicToken;
    }

    public void setSessionExpiryHook(SessionExpiryHook hook) {
        this.sessionExpiryHook = hook;
    }

    public SessionExpiryHook getSessionExpiryHook() {
        return sessionExpiryHook;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public String getApiKey() {
        return apiKey;
    }

    public Profile getProfile() {
        Profile profile = new Profile();
        profile.userName = userId != null ? userId : "stub-user";
        return profile;
    }

    public Margin getMargins(String segment) {
        Margin margin = new Margin();
        margin.available.cash = 100000.0;
        margin.utilised.debits = 1000.0;
        margin.utilised.m2mUnrealised = 50.0;
        return margin;
    }

    public List<MarginCalculationData> getMarginCalculation(List<MarginCalculationParams> params) {
        MarginCalculationData data = new MarginCalculationData();
        data.total = 25000.0;
        data.leverage = 5.0;
        return Collections.singletonList(data);
    }

    public CombinedMarginData getCombinedMarginCalculation(List<MarginCalculationParams> params, boolean considerGreeks, boolean compact) {
        CombinedMarginData data = new CombinedMarginData();
        data.initialMargin.total = 12345.0;
        return data;
    }

    public List<ContractNote> getVirtualContractNote(List<ContractNoteParams> params) {
        ContractNote note = new ContractNote();
        note.charges.total = 25.50;
        return Collections.singletonList(note);
    }

    public Map<String, String> placeOrder(OrderParams params, String variety) {
        LAST_ORDER_PARAMS = params;
        LAST_VARIETY = variety;
        String orderId = "OID" + orderSequence.incrementAndGet();
        Order order = new Order();
        order.orderId = orderId;
        order.tradingsymbol = params.tradingsymbol;
        ORDERS.add(order);
        Map<String, String> response = new HashMap<>();
        response.put("order_id", orderId);
        return response;
    }

    public Map<String, String> placeIcebergOrder(OrderParams params, String variety) {
        return placeOrder(params, variety);
    }

    public Order placeAuctionOrder(OrderParams params, String variety) {
        Map<String, String> response = placeOrder(params, variety);
        Order order = new Order();
        order.orderId = response.get("order_id");
        order.tradingsymbol = params.tradingsymbol;
        return order;
    }

    public Map<String, String> placeCoverOrder(OrderParams params, String variety) {
        return placeOrder(params, variety);
    }

    public List<BulkOrderResponse> placeAutoSliceOrder(OrderParams params, String variety) {
        BulkOrderResponse success = new BulkOrderResponse();
        success.orderId = "BULK" + orderSequence.incrementAndGet();
        BulkOrderResponse failure = new BulkOrderResponse();
        failure.bulkOrderError = new BulkOrderResponse.BulkOrderError();
        failure.bulkOrderError.code = "ERR";
        failure.bulkOrderError.message = "Slice failed";
        return Arrays.asList(success, failure);
    }

    public Map<String, String> placeMarketProtectionOrder(OrderParams params, String variety) {
        return placeOrder(params, variety);
    }

    public Map<String, TriggerRange> getTriggerRange(String[] instruments, String transactionType) {
        Map<String, TriggerRange> ranges = new HashMap<>();
        for (String instrument : instruments) {
            TriggerRange range = new TriggerRange();
            range.lower = 100.0;
            range.upper = 200.0;
            range.percentage = 2.5;
            ranges.put(instrument, range);
        }
        return ranges;
    }

    public List<AuctionInstrument> getAuctionInstruments() {
        AuctionInstrument instrument = new AuctionInstrument();
        instrument.tradingSymbol = "ITC";
        instrument.quantity = 10;
        return Collections.singletonList(instrument);
    }

    public Order cancelOrder(String orderId, String variety) {
        CANCELLED.add(orderId + ":" + variety);
        Order order = new Order();
        order.orderId = orderId;
        return order;
    }

    public Order cancelOrder(String orderId, String parentOrderId, String variety) {
        CANCELLED.add(orderId + ":" + parentOrderId + ":" + variety);
        Order order = new Order();
        order.orderId = orderId;
        return order;
    }

    public Order modifyOrder(String orderId, OrderParams orderParams, String variety) {
        Map<String, String> response = placeOrder(orderParams, variety);
        Order order = new Order();
        order.orderId = response.get("order_id");
        return order;
    }

    public List<Order> getOrders() {
        return new ArrayList<>(ORDERS);
    }

    public List<Order> getOrderHistory(String orderId) {
        Order order = new Order();
        order.orderId = orderId;
        order.status = "COMPLETE";
        return Collections.singletonList(order);
    }

    public List<Trade> getTrades() {
        Trade trade = new Trade();
        trade.tradingSymbol = "INFY";
        return Collections.singletonList(trade);
    }

    public List<Trade> getOrderTrades(String orderId) {
        return tradesByOrder.getOrDefault(orderId, Collections.emptyList());
    }

    public Map<String, List<Position>> getPositions() {
        Position position = new Position();
        position.tradingSymbol = "INFY";
        position.averagePrice = 1500.0;
        Map<String, List<Position>> positions = new HashMap<>();
        positions.put("net", Collections.singletonList(position));
        positions.put("day", Collections.singletonList(position));
        return positions;
    }

    public List<Holding> getHoldings() {
        Holding holding = new Holding();
        holding.tradingSymbol = "INFY";
        holding.dayChange = 12.5;
        holding.dayChangePercentage = 1.5;
        holding.mtf.quantity = 5;
        holding.mtf.averagePrice = 1400.0;
        return Collections.singletonList(holding);
    }

    public JSONObject convertPosition(String tradingsymbol, String exchange, String transactionType, String positionType, String oldProduct, String newProduct, int quantity) {
        return new JSONObject().put("tradingsymbol", tradingsymbol).put("status", "CONVERTED");
    }

    public HistoricalData getHistoricalData(Date from, Date to, String instrumentToken, String interval, boolean continuous, boolean oi) {
        HistoricalData data = new HistoricalData();
        HistoricalData.QuoteData entry = new HistoricalData.QuoteData();
        entry.volume = 1000;
        entry.oi = 2000;
        data.dataArrayList.add(entry);
        return data;
    }

    public List<Instrument> getInstruments() {
        Instrument instrument = new Instrument();
        instrument.tradingSymbol = "INFY";
        return Collections.singletonList(instrument);
    }

    public List<Instrument> getInstruments(String exchange) {
        return getInstruments();
    }

    public Map<String, Quote> getQuote(String[] instruments) {
        Map<String, Quote> response = new HashMap<>();
        for (String instrument : instruments) {
            if (QUOTES.containsKey(instrument)) {
                response.put(instrument, QUOTES.get(instrument));
            } else {
                Quote quote = new Quote();
                quote.lastPrice = 100.0;
                response.put(instrument, quote);
            }
        }
        return response;
    }

    public Map<String, Quote> getOHLC(String[] instruments) {
        Map<String, Quote> response = new HashMap<>();
        for (String instrument : instruments) {
            response.put(instrument, ohlc.getOrDefault(instrument, QUOTES.getOrDefault(instrument, new Quote())));
        }
        return response;
    }

    public Map<String, Quote> getLTP(String[] instruments) {
        Map<String, Quote> response = new HashMap<>();
        for (String instrument : instruments) {
            response.put(instrument, LTPS.getOrDefault(instrument, QUOTES.getOrDefault(instrument, new Quote())));
        }
        return response;
    }

    public List<GTT> getGTTs() {
        return new ArrayList<>(gtts.values());
    }

    public GTT getGTT(int gttId) throws KiteException {
        GTT gtt = gtts.get(String.valueOf(gttId));
        if (gtt == null) {
            throw new KiteException("NOT_FOUND", "GTT not found");
        }
        return gtt;
    }

    public GTT placeGTT(GTTParams params) {
        GTT gtt = new GTT();
        gtt.id = orderSequence.incrementAndGet();
        gtt.condition.exchange = params.exchange;
        gtt.condition.tradingSymbol = params.tradingsymbol;
        gtt.orders = new ArrayList<>(params.orders);
        gtts.put(String.valueOf(gtt.id), gtt);
        return gtt;
    }

    public GTT modifyGTT(int id, GTTParams params) throws KiteException {
        GTT gtt = getGTT(id);
        gtt.orders = new ArrayList<>(params.orders);
        return gtt;
    }

    public GTT cancelGTT(int id) throws KiteException {
        GTT gtt = getGTT(id);
        gtts.remove(String.valueOf(id));
        return gtt;
    }

    public List<MFInstrument> getMFInstruments() {
        MFInstrument instrument = new MFInstrument();
        instrument.tradingsymbol = "INF174K01LS2";
        return Collections.singletonList(instrument);
    }

    public MFOrder placeMFOrder(String tradingsymbol, String transactionType, double amount, double quantity, String tag) {
        MFOrder order = new MFOrder();
        order.orderId = "MF" + orderSequence.incrementAndGet();
        order.tradingsymbol = tradingsymbol;
        mfOrders.put(order.orderId, order);
        return order;
    }

    public void cancelMFOrder(String orderId) {
        mfOrders.remove(orderId);
    }

    public List<MFOrder> getMFOrders() {
        return new ArrayList<>(mfOrders.values());
    }

    public MFOrder getMFOrder(String orderId) throws KiteException {
        MFOrder order = mfOrders.get(orderId);
        if (order == null) {
            throw new KiteException("NOT_FOUND", "MF order not found");
        }
        return order;
    }

    public MFSIP placeMFSIP(String tradingsymbol, String frequency, int instalments, int initialAmount, double amount, double stepUp) {
        MFSIP sip = new MFSIP();
        sip.sipId = "SIP" + orderSequence.incrementAndGet();
        sip.instalments = instalments;
        mfSips.put(sip.sipId, sip);
        return sip;
    }

    public void modifyMFSIP(String frequency, int instalments, int day, double amount, String status, String sipId) {
        MFSIP sip = mfSips.get(sipId);
        if (sip != null) {
            sip.instalments = instalments;
        }
    }

    public void cancelMFSIP(String sipId) {
        mfSips.remove(sipId);
    }

    public List<MFSIP> getMFSIPs() {
        return new ArrayList<>(mfSips.values());
    }

    public MFSIP getMFSIP(String sipId) throws KiteException {
        MFSIP sip = mfSips.get(sipId);
        if (sip == null) {
            throw new KiteException("NOT_FOUND", "MF sip not found");
        }
        return sip;
    }

    public List<MFHolding> getMFHoldings() {
        MFHolding holding = new MFHolding();
        holding.tradingsymbol = "INF174K01LS2";
        return Collections.singletonList(holding);
    }

    public JSONObject logout() {
        return new JSONObject().put("status", "success");
    }
}
