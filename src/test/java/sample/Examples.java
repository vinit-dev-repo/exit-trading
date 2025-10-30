package sample;

import com.neovisionaries.ws.client.WebSocketException;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.*;
import com.zerodhatech.ticker.*;
import org.json.JSONObject;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class Examples {

    public void getProfile(KiteConnect kiteConnect) throws IOException, KiteException {
        Profile profile = kiteConnect.getProfile();
        System.out.println(profile.userName);
    }

    public void getMargins(KiteConnect kiteConnect) throws KiteException, IOException {
        Margin margins = kiteConnect.getMargins("equity");
        System.out.println(margins.available.cash);
        System.out.println(margins.utilised.debits);
        System.out.println(margins.utilised.m2mUnrealised);
    }

    public void getMarginCalculation(KiteConnect kiteConnect) throws IOException, KiteException {
        MarginCalculationParams param = new MarginCalculationParams();
        param.exchange = "NSE";
        param.tradingSymbol = "INFY";
        param.orderType = "MARKET";
        param.quantity = 1;
        param.product = "MIS";
        param.variety = "regular";
        param.transactionType = Constants.TRANSACTION_TYPE_BUY;
        List<MarginCalculationParams> params = new ArrayList<>();
        params.add(param);
        List<MarginCalculationData> data = kiteConnect.getMarginCalculation(params);
        System.out.println(data.get(0).total);
        System.out.println(data.get(0).leverage);
    }

    public void getCombinedMarginCalculation(KiteConnect kiteConnect) throws IOException, KiteException {
        List<MarginCalculationParams> params = new ArrayList<>();

        MarginCalculationParams param = new MarginCalculationParams();
        param.exchange = "NFO";
        param.tradingSymbol = "NIFTY21MARFUT";
        param.orderType = "LIMIT";
        param.quantity = 75;
        param.product = "MIS";
        param.variety = "regular";
        param.transactionType = "BUY";
        param.price = 141819;

        MarginCalculationParams param2 = new MarginCalculationParams();
        param2.exchange = "NFO";
        param2.tradingSymbol = "NIFTY21MAR15000PE";
        param2.orderType = "LIMIT";
        param2.quantity = 75;
        param2.product = "MIS";
        param2.variety = "regular";
        param.transactionType = "BUY";
        param2.price = 300;

        params.add(param);
        params.add(param2);

        CombinedMarginData combinedMarginData = kiteConnect.getCombinedMarginCalculation(params, true, false);
        System.out.println(combinedMarginData.initialMargin.total);
    }

    public void getVirtualContractNote(KiteConnect kiteConnect) throws KiteException, IOException {
        List<ContractNoteParams> virtualContractNoteParams = new ArrayList<>();
        ContractNoteParams contractNoteParams = new ContractNoteParams();
        contractNoteParams.orderID = "230727202226518";
        contractNoteParams.tradingSymbol = "ITC";
        contractNoteParams.exchange = Constants.EXCHANGE_NSE;
        contractNoteParams.product = Constants.PRODUCT_CNC;
        contractNoteParams.orderType = Constants.ORDER_TYPE_MARKET;
        contractNoteParams.variety = Constants.VARIETY_REGULAR;
        contractNoteParams.transactionType = Constants.TRANSACTION_TYPE_SELL;
        contractNoteParams.quantity = 1;
        contractNoteParams.averagePrice = 470.05;
        virtualContractNoteParams.add(contractNoteParams);

        List<ContractNote> data = kiteConnect.getVirtualContractNote(virtualContractNoteParams);
        System.out.println(data.size());
        System.out.println(data.get(0).charges.total);
    }

    public void placeOrder(KiteConnect kiteConnect) throws KiteException, IOException {
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

        Order order = new Order();
        order.orderId = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR).get("order_id");
        System.out.println(order.orderId);
    }

    public void placeIcebergOrder(KiteConnect kiteConnect) throws KiteException, IOException {
        OrderParams orderParams = new OrderParams();
        orderParams.quantity = 10;
        orderParams.orderType = Constants.ORDER_TYPE_LIMIT;
        orderParams.price = 1440.0;
        orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
        orderParams.tradingsymbol = "INFY";
        orderParams.exchange = Constants.EXCHANGE_NSE;
        orderParams.validity = Constants.VALIDITY_TTL;
        orderParams.product = Constants.PRODUCT_MIS;
        orderParams.validityTTL = 10;
        orderParams.icebergLegs = 2;
        orderParams.icebergQuantity = 5;
        Order order = new Order();
        order.orderId = kiteConnect.placeIcebergOrder(orderParams, Constants.VARIETY_ICEBERG).get("order_id");
        System.out.println(order.orderId);
    }

    public void placeCoverOrder(KiteConnect kiteConnect) throws KiteException, IOException {
        OrderParams orderParams = new OrderParams();
        orderParams.price = 0.0;
        orderParams.quantity = 1;
        orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
        orderParams.orderType = Constants.ORDER_TYPE_MARKET;
        orderParams.tradingsymbol = "SOUTHBANK";
        orderParams.exchange = Constants.EXCHANGE_NSE;
        orderParams.validity = Constants.VALIDITY_DAY;
        orderParams.triggerPrice = 30.5;
        orderParams.product = Constants.PRODUCT_MIS;

        Order order = new Order();
        order.orderId = kiteConnect.placeCoverOrder(orderParams, Constants.VARIETY_CO).get("order_id");
        System.out.println(order.orderId);
    }

    public void placeAutoSliceOrder(KiteConnect kiteConnect) throws KiteException, IOException {
        OrderParams orderParams = new OrderParams();
        orderParams.price = 1.0;
        orderParams.quantity = 5925;
        orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
        orderParams.orderType = Constants.ORDER_TYPE_LIMIT;
        orderParams.tradingsymbol = "NIFTY2543025800CE";
        orderParams.exchange = Constants.EXCHANGE_NFO;
        orderParams.validity = Constants.VALIDITY_DAY;
        orderParams.product = Constants.PRODUCT_MIS;

        List<BulkOrderResponse> orders = kiteConnect.placeAutoSliceOrder(orderParams, Constants.VARIETY_REGULAR);
        for (BulkOrderResponse order : orders) {
            if (order.orderId != null) {
                System.out.println(order.orderId);
            } else if (order.bulkOrderError != null) {
                System.out.println(order.bulkOrderError.code);
                System.out.println(order.bulkOrderError.message);
            }
        }
    }

    public void placeMarketProtectionOrder(KiteConnect kiteConnect) throws KiteException, IOException {
        OrderParams orderParams = new OrderParams();
        orderParams.price = 0.0;
        orderParams.quantity = 1;
        orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
        orderParams.orderType = Constants.ORDER_TYPE_MARKET;
        orderParams.tradingsymbol = "INFY";
        orderParams.exchange = Constants.EXCHANGE_NSE;
        orderParams.validity = Constants.VALIDITY_DAY;
        orderParams.product = Constants.PRODUCT_MIS;
        orderParams.marketProtection = -1;

        Order order = new Order();
        order.orderId = kiteConnect.placeMarketProtectionOrder(orderParams, Constants.VARIETY_REGULAR).get("order_id");
        System.out.println(order.orderId);
    }

    public void getTriggerRange(KiteConnect kiteConnect) throws KiteException, IOException {
        String[] instruments = {"BSE:INFY", "NSE:APOLLOTYRE", "NSE:SBIN"};
        Map<String, TriggerRange> triggerRangeMap = kiteConnect.getTriggerRange(instruments, Constants.TRANSACTION_TYPE_BUY);
        System.out.println(triggerRangeMap.get("NSE:SBIN").lower);
        System.out.println(triggerRangeMap.get("NSE:APOLLOTYRE").upper);
        System.out.println(triggerRangeMap.get("BSE:INFY").percentage);
    }

    public void getAuctionInstruments(KiteConnect kiteConnect) throws KiteException, IOException {
        List<AuctionInstrument> auctions = kiteConnect.getAuctionInstruments();
        for (AuctionInstrument auction : auctions) {
            System.out.println(auction.tradingSymbol + " " + auction.quantity);
        }
    }

    public void placeAuctionOrder(KiteConnect kiteConnect) throws KiteException, IOException {
        OrderParams orderParams = new OrderParams();
        orderParams.price = 365.5;
        orderParams.quantity = 1;
        orderParams.transactionType = Constants.TRANSACTION_TYPE_SELL;
        orderParams.orderType = Constants.ORDER_TYPE_LIMIT;
        orderParams.tradingsymbol = "ITC";
        orderParams.exchange = Constants.EXCHANGE_NSE;
        orderParams.validity = Constants.VALIDITY_DAY;
        orderParams.product = Constants.PRODUCT_CNC;
        orderParams.tag = "auction";
        Order order = kiteConnect.placeAuctionOrder(orderParams, Constants.VARIETY_AUCTION);
        System.out.println(order.orderId);
    }

    public void getOrders(KiteConnect kiteConnect) throws KiteException, IOException {
        List<Order> orders = kiteConnect.getOrders();
        for (Order order : orders) {
            System.out.println(order.tradingsymbol + " " + order.orderId + " " + order.orderType);
        }
        System.out.println("list of orders size is " + orders.size());
    }

    public void getOrder(KiteConnect kiteConnect) throws KiteException, IOException {
        List<Order> orders = kiteConnect.getOrderHistory("180111000561605");
        for (Order order : orders) {
            System.out.println(order.orderId + " " + order.status);
        }
        System.out.println("list size is " + orders.size());
    }

    public void getTrades(KiteConnect kiteConnect) throws KiteException, IOException {
        List<Trade> trades = kiteConnect.getTrades();
        for (Trade trade : trades) {
            System.out.println(trade.tradingSymbol + " " + trades.size());
        }
        System.out.println(trades.size());
    }

    public void getTradesWithOrderId(KiteConnect kiteConnect) throws KiteException, IOException {
        List<Trade> trades = kiteConnect.getOrderTrades("180111000561605");
        System.out.println(trades.size());
    }

    public void modifyOrder(KiteConnect kiteConnect) throws KiteException, IOException {
        OrderParams orderParams = new OrderParams();
        orderParams.quantity = 1;
        orderParams.orderType = Constants.ORDER_TYPE_LIMIT;
        orderParams.tradingsymbol = "ASHOKLEY";
        orderParams.product = Constants.PRODUCT_CNC;
        orderParams.exchange = Constants.EXCHANGE_NSE;
        orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
        orderParams.validity = Constants.VALIDITY_DAY;
        orderParams.price = 122.25;

        Order order = kiteConnect.modifyOrder("180116000984900", orderParams, Constants.VARIETY_REGULAR);
        System.out.println(order.orderId);
    }

    public void cancelOrder(KiteConnect kiteConnect) throws KiteException, IOException {
        Order order = kiteConnect.cancelOrder("180116000727266", Constants.VARIETY_REGULAR);
        System.out.println(order.orderId);
    }

    public void exitBracketOrder(KiteConnect kiteConnect) throws KiteException, IOException {
        Order order = kiteConnect.cancelOrder("180116000812153", "180116000798058", Constants.VARIETY_BO);
        System.out.println(order.orderId);
    }

    public void getGTTs(KiteConnect kiteConnect) throws KiteException, IOException {
        List<GTT> gtts = kiteConnect.getGTTs();
        if (!gtts.isEmpty()) {
            System.out.println(gtts.get(0).createdAt);
        }
    }

    public void getGTT(KiteConnect kiteConnect) throws IOException, KiteException {
        GTT gtt = kiteConnect.getGTT(177574);
        System.out.println(gtt.condition.tradingSymbol);
    }

    public void placeGTT(KiteConnect kiteConnect) throws IOException, KiteException {
        GTTParams gttParams = new GTTParams();
        gttParams.triggerType = Constants.OCO;
        gttParams.exchange = "NSE";
        gttParams.tradingsymbol = "SBIN";
        gttParams.lastPrice = 302.95;

        List<Double> triggerPrices = new ArrayList<>();
        triggerPrices.add(290d);
        triggerPrices.add(320d);
        gttParams.triggerPrices = triggerPrices;

        GTTParams.GTTOrderParams order1Params = gttParams.new GTTOrderParams();
        order1Params.orderType = Constants.ORDER_TYPE_LIMIT;
        order1Params.price = 290;
        order1Params.product = Constants.PRODUCT_CNC;
        order1Params.transactionType = Constants.TRANSACTION_TYPE_SELL;
        order1Params.quantity = 0;

        GTTParams.GTTOrderParams order2Params = gttParams.new GTTOrderParams();
        order2Params.orderType = Constants.ORDER_TYPE_LIMIT;
        order2Params.price = 320;
        order2Params.product = Constants.PRODUCT_CNC;
        order2Params.transactionType = Constants.TRANSACTION_TYPE_SELL;
        order2Params.quantity = 1;

        List<GTTParams.GTTOrderParams> ordersList = new ArrayList<>();
        ordersList.add(order1Params);
        ordersList.add(order2Params);
        gttParams.orders = ordersList;

        GTT gtt = kiteConnect.placeGTT(gttParams);
        System.out.println(gtt.id);
    }

    public void modifyGTT(KiteConnect kiteConnect) throws IOException, KiteException {
        GTTParams gttParams = new GTTParams();
        gttParams.triggerType = Constants.OCO;
        gttParams.exchange = "NSE";
        gttParams.tradingsymbol = "SBIN";
        gttParams.lastPrice = 302.95;

        List<Double> triggerPrices = new ArrayList<>();
        triggerPrices.add(290d);
        triggerPrices.add(320d);
        gttParams.triggerPrices = triggerPrices;

        GTTParams.GTTOrderParams order1Params = gttParams.new GTTOrderParams();
        order1Params.orderType = Constants.ORDER_TYPE_LIMIT;
        order1Params.price = 290;
        order1Params.product = Constants.PRODUCT_CNC;
        order1Params.transactionType = Constants.TRANSACTION_TYPE_SELL;
        order1Params.quantity = 1;

        GTTParams.GTTOrderParams order2Params = gttParams.new GTTOrderParams();
        order2Params.orderType = Constants.ORDER_TYPE_LIMIT;
        order2Params.price = 320;
        order2Params.product = Constants.PRODUCT_CNC;
        order2Params.transactionType = Constants.TRANSACTION_TYPE_SELL;
        order2Params.quantity = 1;

        List<GTTParams.GTTOrderParams> ordersList = new ArrayList<>();
        ordersList.add(order1Params);
        ordersList.add(order2Params);
        gttParams.orders = ordersList;

        GTT gtt = kiteConnect.modifyGTT(177574, gttParams);
        System.out.println(gtt.id);
    }

    public void cancelGTT(KiteConnect kiteConnect) throws IOException, KiteException {
        GTT gtt = kiteConnect.cancelGTT(175859);
        System.out.println(gtt.id);
    }

    public void getPositions(KiteConnect kiteConnect) throws KiteException, IOException {
        Map<String, List<Position>> position = kiteConnect.getPositions();
        System.out.println(position.get("net").size());
        System.out.println(position.get("day").size());
        System.out.println(position.get("net").get(0).averagePrice);
    }

    public void getHoldings(KiteConnect kiteConnect) throws KiteException, IOException {
        List<Holding> holdings = kiteConnect.getHoldings();
        System.out.println(holdings.size());
        System.out.println(holdings.get(0).tradingSymbol);
        System.out.println(holdings.get(0).dayChange);
        System.out.println(holdings.get(0).dayChangePercentage);
    }

    public void getMTFHoldings(KiteConnect kiteConnect) throws KiteException, IOException {
        List<Holding> holdings = kiteConnect.getHoldings();
        List<Holding> mtfHoldings = new ArrayList<>();
        for (Holding holding : holdings) {
            if (holding.mtf.quantity > 0) {
                mtfHoldings.add(holding);
            }
        }
        System.out.println(mtfHoldings.size());
        if (!mtfHoldings.isEmpty()) {
            System.out.println(mtfHoldings.get(0).tradingSymbol);
            System.out.println(mtfHoldings.get(0).mtf.quantity);
            System.out.println(mtfHoldings.get(0).mtf.averagePrice);
        }
    }

    public void converPosition(KiteConnect kiteConnect) throws KiteException, IOException {
        JSONObject jsonObject = kiteConnect.convertPosition("ASHOKLEY", Constants.EXCHANGE_NSE, Constants.TRANSACTION_TYPE_BUY, Constants.POSITION_DAY, Constants.PRODUCT_MIS, Constants.PRODUCT_CNC, 1);
        System.out.println(jsonObject);
    }

    public void getAllInstruments(KiteConnect kiteConnect) throws KiteException, IOException {
        List<Instrument> instruments = kiteConnect.getInstruments();
        System.out.println(instruments.size());
    }

    public void getInstrumentsForExchange(KiteConnect kiteConnect) throws KiteException, IOException {
        List<Instrument> nseInstruments = kiteConnect.getInstruments("CDS");
        System.out.println(nseInstruments.size());
    }

    public void getQuote(KiteConnect kiteConnect) throws KiteException, IOException {
        String[] instruments = {"256265", "BSE:INFY", "NSE:APOLLOTYRE", "NSE:NIFTY 50", "24507906"};
        Map<String, Quote> quotes = kiteConnect.getQuote(instruments);
        System.out.println(quotes.get("NSE:APOLLOTYRE").instrumentToken + "");
        System.out.println(quotes.get("NSE:APOLLOTYRE").oi + "");
        System.out.println(quotes.get("NSE:APOLLOTYRE").depth.buy.get(4).getPrice());
        System.out.println(quotes.get("NSE:APOLLOTYRE").timestamp);
        System.out.println(quotes.get("NSE:APOLLOTYRE").lowerCircuitLimit + "");
        System.out.println(quotes.get("NSE:APOLLOTYRE").upperCircuitLimit + "");
        System.out.println(quotes.get("24507906").oiDayHigh);
        System.out.println(quotes.get("24507906").oiDayLow);
    }

    public void getOHLC(KiteConnect kiteConnect) throws KiteException, IOException {
        String[] instruments = {"256265", "BSE:INFY", "NSE:INFY", "NSE:NIFTY 50"};
        System.out.println(kiteConnect.getOHLC(instruments).get("256265").lastPrice);
        System.out.println(kiteConnect.getOHLC(instruments).get("NSE:NIFTY 50").ohlc.open);
    }

    public void getLTP(KiteConnect kiteConnect) throws KiteException, IOException {
        String[] instruments = {"256265", "BSE:INFY", "NSE:INFY", "NSE:NIFTY 50"};
        System.out.println(kiteConnect.getLTP(instruments).get("256265").lastPrice);
    }

    public void getHistoricalData(KiteConnect kiteConnect) throws KiteException, IOException {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date from = new Date();
        Date to = new Date();
        try {
            from = formatter.parse("2019-09-20 09:15:00");
            to = formatter.parse("2019-09-20 15:30:00");
        } catch (ParseException e) {
            e.printStackTrace();
        }
        HistoricalData historicalData = kiteConnect.getHistoricalData(from, to, "54872327", "15minute", false, true);
        System.out.println(historicalData.dataArrayList.size());
        if (!historicalData.dataArrayList.isEmpty()) {
            System.out.println(historicalData.dataArrayList.get(0).volume);
            System.out.println(historicalData.dataArrayList.get(historicalData.dataArrayList.size() - 1).volume);
            System.out.println(historicalData.dataArrayList.get(0).oi);
        }
    }

    public void logout(KiteConnect kiteConnect) throws KiteException, IOException {
        JSONObject jsonObject = kiteConnect.logout();
        System.out.println(jsonObject);
    }

    public void getMFInstruments(KiteConnect kiteConnect) throws KiteException, IOException {
        List<MFInstrument> mfList = kiteConnect.getMFInstruments();
        System.out.println("size of mf instrument list: " + mfList.size());
    }

    public void getMFHoldings(KiteConnect kiteConnect) throws KiteException, IOException {
        List<MFHolding> mfHoldings = kiteConnect.getMFHoldings();
        System.out.println("mf holdings " + mfHoldings.size());
    }

    public void placeMFOrder(KiteConnect kiteConnect) throws KiteException, IOException {
        System.out.println("place order: " + kiteConnect.placeMFOrder("INF174K01LS2", Constants.TRANSACTION_TYPE_BUY, 5000, 0, "myTag").orderId);
    }

    public void cancelMFOrder(KiteConnect kiteConnect) throws KiteException, IOException {
        kiteConnect.cancelMFOrder("668604240868430");
        System.out.println("cancel order successful");
    }

    public void getMFOrders(KiteConnect kiteConnect) throws KiteException, IOException {
        List<MFOrder> mfOrders = kiteConnect.getMFOrders();
        System.out.println("mf orders: " + mfOrders.size());
    }

    public void getMFOrder(KiteConnect kiteConnect) throws KiteException, IOException {
        try {
            System.out.println("mf order: " + kiteConnect.getMFOrder("106580291331583").tradingsymbol);
        } catch (KiteException ex) {
            System.out.println(ex.getMessage());
        }
    }

    public void placeMFSIP(KiteConnect kiteConnect) throws KiteException, IOException {
        System.out.println("mf place sip: " + kiteConnect.placeMFSIP("INF174K01LS2", "monthly", 1, -1, 5000, 1000).sipId);
    }

    public void modifyMFSIP(KiteConnect kiteConnect) throws KiteException, IOException {
        kiteConnect.modifyMFSIP("weekly", 1, 5, 1000, "active", "504341441825418");
    }

    public void cancelMFSIP(KiteConnect kiteConnect) throws KiteException, IOException {
        kiteConnect.cancelMFSIP("504341441825418");
        System.out.println("cancel sip successful");
    }

    public void getMFSIPS(KiteConnect kiteConnect) throws KiteException, IOException {
        List<MFSIP> sips = kiteConnect.getMFSIPs();
        System.out.println("mf sips: " + sips.size());
    }

    public void getMFSIP(KiteConnect kiteConnect) throws KiteException, IOException {
        try {
            System.out.println("mf sip: " + kiteConnect.getMFSIP("291156521960679").instalments);
        } catch (KiteException ex) {
            System.out.println(ex.getMessage());
        }
    }

    public void tickerUsage(KiteConnect kiteConnect, ArrayList<Long> tokens) throws IOException, WebSocketException, KiteException {
        final KiteTicker tickerProvider = new KiteTicker(kiteConnect.getAccessToken(), kiteConnect.getApiKey());

        tickerProvider.setOnConnectedListener(new OnConnect() {
            @Override
            public void onConnected() {
                tickerProvider.subscribe(tokens);
                tickerProvider.setMode(tokens, KiteTicker.modeFull);
            }
        });

        tickerProvider.setOnDisconnectedListener(new OnDisconnect() {
            @Override
            public void onDisconnected() {
            }
        });

        tickerProvider.setOnOrderUpdateListener(new OnOrderUpdate() {
            @Override
            public void onOrderUpdate(Order order) {
                System.out.println("order update " + order.orderId);
            }
        });

        tickerProvider.setOnErrorListener(new OnError() {
            @Override
            public void onError(Exception exception) {
            }

            @Override
            public void onError(KiteException kiteException) {
            }

            @Override
            public void onError(String error) {
                System.out.println(error);
            }
        });

        tickerProvider.setOnTickerArrivalListener(new OnTicks() {
            @Override
            public void onTicks(ArrayList<Tick> ticks) {
                NumberFormat formatter = new DecimalFormat();
                System.out.println("ticks size " + ticks.size());
                if (ticks.size() > 0) {
                    System.out.println("last price " + ticks.get(0).getLastTradedPrice());
                    System.out.println("open interest " + formatter.format(ticks.get(0).getOi()));
                    System.out.println("day high OI " + formatter.format(ticks.get(0).getOpenInterestDayHigh()));
                    System.out.println("day low OI " + formatter.format(ticks.get(0).getOpenInterestDayLow()));
                    System.out.println("change " + formatter.format(ticks.get(0).getChange()));
                    System.out.println("tick timestamp " + ticks.get(0).getTickTimestamp());
                    System.out.println("last traded time " + ticks.get(0).getLastTradedTime());
                    System.out.println(ticks.get(0).getMarketDepth().get("buy").size());
                }
            }
        });

        tickerProvider.setTryReconnection(true);
        tickerProvider.setMaximumRetries(10);
        tickerProvider.setMaximumRetryInterval(30);
        tickerProvider.connect();
        boolean isConnected = tickerProvider.isConnectionOpen();
        System.out.println(isConnected);
        tickerProvider.setMode(tokens, KiteTicker.modeLTP);
        tickerProvider.unsubscribe(tokens);
        tickerProvider.disconnect();
    }
}
