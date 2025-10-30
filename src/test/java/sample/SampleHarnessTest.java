package sample;

import com.neovisionaries.ws.client.WebSocketException;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.SessionExpiryHook;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import org.json.JSONException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThatCode;

class SampleHarnessTest {

    private Examples examples;
    private KiteConnect kiteConnect;

    @BeforeEach
    void setUp() {
        examples = new Examples();
        kiteConnect = new KiteConnect("xxxxyyyyzzzz");
        kiteConnect.setUserId("xxxxx");
        kiteConnect.setSessionExpiryHook(new SessionExpiryHook() {
            @Override
            public void sessionExpired() {
                System.out.println("session expired");
            }
        });
        User user = kiteConnect.generateSession("xxxxxtttyyy", "xxxxxxxyyyyy");
        kiteConnect.setAccessToken(user.accessToken);
        kiteConnect.setPublicToken(user.publicToken);
    }

    @Test
    void shouldExecuteSampleFlowWithoutExceptions() {
        assertThatCode(() -> {
            runSampleCalls();
        }).doesNotThrowAnyException();
    }

    private void runSampleCalls() throws KiteException, JSONException, IOException, WebSocketException {
        examples.getProfile(kiteConnect);
        examples.getMargins(kiteConnect);
        examples.getMarginCalculation(kiteConnect);
        examples.getCombinedMarginCalculation(kiteConnect);
        examples.getVirtualContractNote(kiteConnect);
        examples.placeOrder(kiteConnect);
        examples.placeIcebergOrder(kiteConnect);
        examples.placeCoverOrder(kiteConnect);
        examples.placeAutoSliceOrder(kiteConnect);
        examples.placeMarketProtectionOrder(kiteConnect);
        examples.getTriggerRange(kiteConnect);
        examples.getAuctionInstruments(kiteConnect);
        examples.placeAuctionOrder(kiteConnect);
        examples.getOrders(kiteConnect);
        examples.getOrder(kiteConnect);
        examples.getTrades(kiteConnect);
        examples.getTradesWithOrderId(kiteConnect);
        examples.modifyOrder(kiteConnect);
        examples.cancelOrder(kiteConnect);
        examples.exitBracketOrder(kiteConnect);
        examples.getGTTs(kiteConnect);
        examples.getGTT(kiteConnect);
        examples.placeGTT(kiteConnect);
        examples.modifyGTT(kiteConnect);
        examples.cancelGTT(kiteConnect);
        examples.getPositions(kiteConnect);
        examples.getHoldings(kiteConnect);
        examples.getMTFHoldings(kiteConnect);
        examples.converPosition(kiteConnect);
        examples.getHistoricalData(kiteConnect);
        examples.getAllInstruments(kiteConnect);
        examples.getInstrumentsForExchange(kiteConnect);
        examples.getQuote(kiteConnect);
        examples.getOHLC(kiteConnect);
        examples.getLTP(kiteConnect);
        examples.getMFInstruments(kiteConnect);
        examples.placeMFOrder(kiteConnect);
        examples.cancelMFOrder(kiteConnect);
        examples.getMFOrders(kiteConnect);
        examples.getMFOrder(kiteConnect);
        examples.placeMFSIP(kiteConnect);
        examples.modifyMFSIP(kiteConnect);
        examples.cancelMFSIP(kiteConnect);
        examples.getMFSIPS(kiteConnect);
        examples.getMFSIP(kiteConnect);
        examples.getMFHoldings(kiteConnect);
        examples.logout(kiteConnect);
        ArrayList<Long> tokens = new ArrayList<>();
        tokens.add(Long.parseLong("256265"));
        examples.tickerUsage(kiteConnect, tokens);
    }
}
