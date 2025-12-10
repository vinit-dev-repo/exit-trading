package com.exittrading.app.service.impact;

import com.exittrading.app.dto.DepthView;
import com.exittrading.app.dto.LiquidityImpactResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class LiquidityImpactService {

    private static final Logger ilog = LoggerFactory.getLogger("impact");
    private final com.exittrading.app.service.auction.AuctionAnalysisService analysisService;

    public LiquidityImpactService(com.exittrading.app.service.auction.AuctionAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    public List<LiquidityImpactResponse> compute(List<DepthView> inputs,
                                                 String stageOverride,
                                                 String auctionPhase,
                                                 Double tickOverride,
                                                 Long qrefOverride) {
        List<LiquidityImpactResponse> out = new ArrayList<>();
        if (inputs == null) return out;
        for (DepthView v : inputs) {
            if (v == null) continue;
            out.add(computeOne(v, stageOverride, auctionPhase, tickOverride, qrefOverride));
        }
        return out;
    }

    private LiquidityImpactResponse computeOne(DepthView v,
                                               String stageOverride,
                                               String auctionPhase,
                                               Double tickOverride,
                                               Long qrefOverride) {
        String symbol = safe(v.getTradingsymbol());
        double L = v.getLowerCircuit() != null ? v.getLowerCircuit().doubleValue() : Double.NaN;
        double U = v.getUpperCircuit() != null ? v.getUpperCircuit().doubleValue() : Double.NaN;
        double ltp = v.getLtp() != null ? v.getLtp().doubleValue() : Double.NaN;
        double prevClose = v.getPrevClose() != null ? v.getPrevClose().doubleValue() : (isFinite(L) && isFinite(U) ? (L + U) / 2.0 : Double.NaN);

        // Clean ladders (top-5), merge duplicate prices
        // Clean ladders (top-5), merge duplicate prices
        List<Level> bids = normalizeLevels(v.getBuyLevels(), true);
        List<Level> asks = normalizeLevels(v.getSellLevels(), false);

        Double B1 = bids.isEmpty() ? null : bids.get(0).price;
        Double A1 = asks.isEmpty() ? null : asks.get(0).price;
        double spread = (B1 != null && A1 != null) ? Math.max(0.0, A1 - B1) : 0.0;

        double tick = tickOverride != null ? tickOverride : (v.getTick() != null ? v.getTick() : inferTick((A1 != null ? A1 : (B1 != null ? B1 : ltp))));
        String stage = stageOverride != null ? stageOverride : inferStage(ltp, L, U, tick);
        double pBand = inferBandP(L, U, prevClose);

        long buyQtyTotal = Math.max(0L, v.getBuyQuantity());
        long sellQtyTotal = Math.max(0L, v.getSellQuantity());
        long ltq = v.getLtq() != null ? Math.max(0L, v.getLtq()) : 0L;
        long exitQty = v.getExitQuantity() != null ? Math.max(0L, v.getExitQuantity()) : 0L;
        long baseQref = defaultQref(ltq, buyQtyTotal + sellQtyTotal, bids, asks, L, U, tick, B1, A1);
        long qref = qrefOverride != null ? qrefOverride : Math.max(exitQty, baseQref);

        // IOI and spread fragility
        long sumBidTop5 = bids.stream().mapToLong(l -> l.qty).sum();
        long sumAskTop5 = asks.stream().mapToLong(l -> l.qty).sum();
        double denom = (double) Math.max(1L, sumBidTop5 + sumAskTop5);
        double IOI = (sumBidTop5 - sumAskTop5) / denom;
        double psi = Math.min(1.0, (spread) / Math.max(tick * 3.0, 1e-9));
        double ltpVal = v.getLtp() != null ? v.getLtp().doubleValue() : Double.NaN;
        boolean bandTouch = (isFinite(L) && ((isFinite(ltpVal) && ltpVal <= L + tick) || (B1 != null && B1 <= L + tick)));
        boolean askAtBand = (isFinite(L) && A1 != null && A1 <= L + tick);
        boolean askDominant = sumBidTop5 < Math.max(5000L, Math.round(0.02 * sumAskTop5));
        MaxOrder maxBid = maxOrder(bids);
        MaxOrder maxAsk = maxOrder(asks);
        long maxBidOrder = maxBid != null ? maxBid.qty : 0L;
        long maxAskOrder = maxAsk != null ? maxAsk.qty : 0L;
        boolean bidStarved = sumBidTop5 < Math.max(5000L, Math.round(0.01 * sumAskTop5));

        // Deltas
        LiquidityImpactResponse.Deltas deltas = new LiquidityImpactResponse.Deltas();
        deltas.sell = new LiquidityImpactResponse.SideDeltas();
        deltas.buy = new LiquidityImpactResponse.SideDeltas();

        if ("ESM-2".equalsIgnoreCase(stage)) {
            AuctionContext auc = buildAuction(bids, asks, L, U);
            double p0 = auc.p0;
            // SELL: one tick down
            double pStarSellTick = clamp(p0 - tick, L, U);
            int qSellTick = (int) Math.max(0, 1 + auc.maxGapDminusSAbove(pStarSellTick));
            // SELL: to band L
            int qSellBand = (int) Math.max(0, 1 + auc.maxGapDminusSAbove(L));
            // BUY symmetric
            double pStarBuyTick = clamp(p0 + tick, L, U);
            int qBuyTick = (int) Math.max(0, 1 + auc.maxGapSminusDFrom(pStarBuyTick));
            int qBuyBand = (int) Math.max(0, 1 + auc.maxGapSminusDFrom(U));

            deltas.sell.oneTick = point(qSellTick, pStarSellTick);
            deltas.sell.toBand = point(qSellBand, L);
            deltas.buy.oneTick = point(qBuyTick, pStarBuyTick);
            deltas.buy.toBand = point(qBuyBand, U);

            // If p0 already at band, clip delta to zero on that side
            if (almostEq(p0, L, tick/2)) deltas.sell.oneTick = point(0, L);
            if (almostEq(p0, U, tick/2)) deltas.buy.oneTick = point(0, U);
        } else {
            // ESM-1 continuous
            // SELL: one tick = all at best bid; new price = next lower bid or L
            if (!bids.isEmpty()) {
                double b1 = bids.get(0).price;
                int q = bids.stream().filter(l -> almostEq(l.price, b1, tick/2)).mapToInt(l -> (int) l.qty).sum();
                double next = bids.size() > 1 ? bids.get(1).price : L;
                deltas.sell.oneTick = point(q, clamp(next, L, U));
            } else {
                deltas.sell.oneTick = null;
            }
            // SELL: to-band L => consume bids with b >= max(L+tick, L)
            int qBandSell = bids.stream().filter(l -> l.price >= Math.max(L + tick, L)).mapToInt(l -> (int) l.qty).sum();
            deltas.sell.toBand = point(qBandSell, L);

            // BUY symmetric
            if (!asks.isEmpty()) {
                double a1 = asks.get(0).price;
                int q = asks.stream().filter(l -> almostEq(l.price, a1, tick/2)).mapToInt(l -> (int) l.qty).sum();
                double next = asks.size() > 1 ? asks.get(1).price : U;
                deltas.buy.oneTick = point(q, clamp(next, L, U));
            } else {
                deltas.buy.oneTick = null;
            }
            int qBandBuy = asks.stream().filter(l -> l.price <= Math.min(U - tick, U)).mapToInt(l -> (int) l.qty).sum();
            deltas.buy.toBand = point(qBandBuy, U);
        }

        // VWAP and terminal ticks for Qref
        VwapSide sellV = vwapSell(bids, qref, L);
        VwapSide buyV = vwapBuy(asks, qref, U);

        double dPctSell = (B1 != null && sellV.vwap > 0) ? clamp01((B1 - sellV.vwap) / Math.max(B1, 1e-9)) : 0.0;
        double dPctBuy = (A1 != null && buyV.vwap > 0) ? clamp01((buyV.vwap - A1) / Math.max(A1, 1e-9)) : 0.0;
        double vSell = Math.min(1.0, dPctSell / Math.max(pBand, 1e-9));
        double vBuy = Math.min(1.0, dPctBuy / Math.max(pBand, 1e-9));

        int ticksToBandSell = (B1 != null && isFinite(L) && isFinite(tick)) ? Math.max(1, (int)Math.floor((B1 - L) / tick)) : 1;
        int ticksToBandBuy = (A1 != null && isFinite(U) && isFinite(tick)) ? Math.max(1, (int)Math.floor((U - A1) / tick)) : 1;
        int movedSell = (B1 != null) ? Math.max(0, (int)Math.floor((B1 - sellV.terminalBest) / Math.max(tick, 1e-9))) : 0;
        int movedBuy = (A1 != null) ? Math.max(0, (int)Math.floor((buyV.terminalBest - A1) / Math.max(tick, 1e-9))) : 0;
        double tSell = Math.min(1.0, (double)movedSell / ticksToBandSell);
        double tBuy = Math.min(1.0, (double)movedBuy / ticksToBandBuy);

        long QvisDown = bids.stream().filter(l -> l.price >= L).mapToLong(l -> l.qty).sum();
        long QvisUp = asks.stream().filter(l -> l.price <= U).mapToLong(l -> l.qty).sum();
        double dSell = Math.min(1.0, QvisDown > 0 ? (double) qref / (double) QvisDown : 0.0);
        double dBuy = Math.min(1.0, QvisUp > 0 ? (double) qref / (double) QvisUp : 0.0);
        boolean partialDepth = bids.size() < 5 || asks.size() < 5 || !isFinite(L) || !isFinite(U);
        double depthConfidence = partialDepth ? 0.8 : 1.0;
        if (bidStarved) depthConfidence *= 0.5;

        // Stage factor
        double F = 1.0;
        if ("ESM-2".equalsIgnoreCase(stage)) {
            if (auctionPhase != null && (auctionPhase.equalsIgnoreCase("buffer") || auctionPhase.equalsIgnoreCase("uncross"))) F = 1.10;
            else F = 1.20;
        }

        // Composite scores (Liquidity Impact only)
        double w_t = 0.45, w_v = 0.35, w_d = 0.10, w_psi = 0.05, w_imb = 0.05;
        double sellScore = 100.0 * F * clamp01(w_t * tSell + w_v * vSell + w_d * dSell + w_psi * psi + w_imb * Math.max(0.0, -IOI));
        double buyScore  = 100.0 * F * clamp01(w_t * tBuy  + w_v * vBuy  + w_d * dBuy  + w_psi * psi + w_imb * Math.max(0.0,  IOI));

        // Legend
        String legend = legendFor(Math.max(sellScore, buyScore));

        // Response assembly
        LiquidityImpactResponse r = new LiquidityImpactResponse();
        r.symbol = symbol;
        r.tick = tick;
        r.best = new LiquidityImpactResponse.Best();
        r.best.bid = B1;
        r.best.ask = A1;
        r.best.spread = spread;
        r.band = new LiquidityImpactResponse.Band();
        r.band.L = isFinite(L) ? L : null;
        r.band.U = isFinite(U) ? U : null;
        r.stage = stage;
        r.auctionPhase = auctionPhase;
        r.qref = qref;
        r.deltas = deltas;
        r.scores = new LiquidityImpactResponse.Scores();
        r.scores.sell = round2(sellScore);
        r.scores.buy = round2(buyScore);
        r.scores.legend = legend;

        // Microstructure: OBI%, Swing, Micro score via AuctionAnalysisService
        LiquidityImpactResponse.Micro micro = new LiquidityImpactResponse.Micro();
        try {
            java.util.List<com.exittrading.app.dto.DepthView> single = java.util.List.of(v);
            java.util.List<com.exittrading.app.dto.DetectionSummary> dsList = analysisService.summarize(single);
            com.exittrading.app.dto.DetectionSummary ds = (dsList != null && !dsList.isEmpty()) ? dsList.get(0) : null;
            double swingSell = ds != null && ds.getSwing() != null ? Math.max(0.0, ds.getSwing()) : 0.0;
            double microSell = ds != null && ds.getSellSpikeScore() != null ? ds.getSellSpikeScore() : 0.0;
            double trigger = analysisService.getScoreTrigger();
            micro.obiPct = round1(IOI * 100.0);
            micro.swingSell = round2(clamp01(swingSell));
            micro.microSell = round2(clamp01(trigger > 0 ? microSell / trigger : microSell));
        } catch (Exception ignored) {
            micro.obiPct = round1(IOI * 100.0);
            micro.swingSell = 0.0; micro.microSell = 0.0;
        }
        // Dump decision
        boolean dump = false; String reason = null;
        int bandTicksSell = Math.max(1, ticksToBandSell);
        boolean nearBand = movedSell >= Math.max(1, Math.round(0.05 * bandTicksSell));
        double combinedBaseline = "ESM-2".equalsIgnoreCase(stage) ? 55.0 : 60.0;
        // Combined score (below) will be computed after, but we can stage decision once combined is known.
        r.notes = buildNotes(spread, tick, stage, IOI, partialDepth || (bids.size() > 0 && bids.get(bids.size()-1).price > L) || (asks.size() > 0 && asks.get(asks.size()-1).price < U));
        r.human = buildHuman(symbol, B1, A1, L, U, IOI, qref, deltas, r.scores, spread, tick, stage);

        // Combined score (Liquidity Impact + trend/time + micro): weights 0.55/0.15/0.15/0.10/0.05
        double liSell = clamp01(sellScore / 100.0);
        double liBuy = clamp01(buyScore / 100.0);
        double swSell = micro.swingSell != null ? micro.swingSell : 0.0;
        double msSell = micro.microSell != null ? micro.microSell : 0.0;
        double obiSell = Math.max(0.0, -IOI);
        double obiBuy = Math.max(0.0, IOI);
        Double drift = v.getDriftBps();
        double trendSell = clamp01(drift != null && drift < 0 ? -drift / 5.0 : 0.0);
        double trendBuy = clamp01(drift != null && drift > 0 ? drift / 5.0 : 0.0);
        double timeSell = bandTouch ? 1.0 : timeScore(v.getTimeToBandSellSec());
        double timeBuy = timeScore(v.getTimeToBandBuySec());
        
        // Velocity factor: high trades/sec in direction of drift
        double ltqps = v.getLtqPerSec() != null ? v.getLtqPerSec() : 0.0;
        double velSell = (drift != null && drift < 0 && ltqps > 0) ? clamp01(ltqps / Math.max(1.0, qref / 10.0)) : 0.0;
        double velBuy = (drift != null && drift > 0 && ltqps > 0) ? clamp01(ltqps / Math.max(1.0, qref / 10.0)) : 0.0;

        double w_li2 = 0.45, w_trend = 0.15, w_time = 0.15, w_obi2 = 0.10, w_sw2 = 0.05, w_ms2 = 0.05, w_vel = 0.05;
        double combinedSell = 100.0 * clamp01(depthConfidence * (w_li2 * liSell + w_trend * trendSell + w_time * timeSell + w_obi2 * obiSell + w_sw2 * swSell + w_ms2 * msSell + w_vel * velSell));
        double combinedBuy  = 100.0 * clamp01(depthConfidence * (w_li2 * liBuy  + w_trend * trendBuy  + w_time * timeBuy  + w_obi2 * obiBuy + w_vel * velBuy));
        r.combined = new LiquidityImpactResponse.Combined();
        r.combined.sell = round2(combinedSell);
        r.combined.buy = round2(combinedBuy);
        r.combined.legend = legendFor(Math.max(combinedSell, combinedBuy));
        // Override legend/score when pinned at band with heavy sell pressure so UI aligns with dump decision
        boolean forceHigh = (bandTouch || askAtBand) && (bidStarved || askDominant || obiSell >= 0.50);
        if (forceHigh) {
            r.scores.legend = "High";
            r.scores.sell = Math.max(r.scores.sell != null ? r.scores.sell : 0.0, 80.0);
            r.combined.legend = "High";
            r.combined.sell = Math.max(r.combined.sell != null ? r.combined.sell : 0.0, 80.0);
        }
        r.driftBps = v.getDriftBps();
        r.ltqPerSec = v.getLtqPerSec();
        r.timeToBandSellSec = v.getTimeToBandSellSec();
        r.timeToBandBuySec = v.getTimeToBandBuySec();
        r.depthConfidence = round2(depthConfidence);
        if (maxBid != null) {
            r.maxBuyOrderQty = maxBid.qty;
            r.maxBuyOrderCount = maxBid.orders;
            r.maxBuyOrderPrice = maxBid.price;
        }
        if (maxAsk != null) {
            r.maxSellOrderQty = maxAsk.qty;
            r.maxSellOrderCount = maxAsk.orders;
            r.maxSellOrderPrice = maxAsk.price;
        }

        // Finalize dump decision using combined score
        // If sitting on/through band with heavy sell pressure, force dump even if combined score is low
        boolean bandForced = (bandTouch || askAtBand) && (bidStarved || askDominant || obiSell >= 0.50 || combinedSell >= 50.0);
        if (bandForced || (combinedSell >= combinedBaseline && (timeSell >= 0.50 || trendSell >= 0.50 || swSell >= 0.30 || msSell >= 0.60) && (obiSell >= 0.25 || nearBand))) {
            dump = true;
            reason = String.format(java.util.Locale.ROOT, "score=%.1f, swing=%.2f, obi=%.0f%%", combinedSell, swSell, micro.obiPct);
        } else {
            dump = false; reason = null;
        }
        micro.dumpSell = dump; micro.dumpReason = reason;
        r.micro = micro;

        // Dedicated logger line (market-hours filtered)
        ilog.info("sym={} | stage={} | phase={} | tick={} | qref={} | B1={} | A1={} | L={} | U={} | dSELL(1t)={}@{} | dBUY(1t)={}@{} | scoreSELL={} | scoreBUY={} | combinedSELL={} | dumpSell={}",
                symbol, stage, (auctionPhase != null ? auctionPhase : "-"), tick, qref,
                fmt(B1), fmt(A1), fmt(L), fmt(U),
                safeQty(deltas.sell.oneTick), fmtPrice(deltas.sell.oneTick),
                safeQty(deltas.buy.oneTick), fmtPrice(deltas.buy.oneTick),
                r.scores.sell, r.scores.buy, r.combined.sell, dump);

        return r;
    }

    // ----- Helpers -----

    private static class Level {
        double price; long qty; int orders;
        Level(double price, long qty, int orders) { this.price = price; this.qty = qty; this.orders = orders; }
    }
    private static class MaxOrder {
        final long qty; final int orders; final double price;
        MaxOrder(long qty, int orders, double price){ this.qty = qty; this.orders = orders; this.price = price; }
    }

    private static class VwapSide {
        double vwap; double terminalBest;
        VwapSide(double vwap, double terminalBest){ this.vwap = vwap; this.terminalBest = terminalBest; }
    }

      private static List<Level> normalizeLevels(List<DepthView.Level> lvls, boolean bids) {
          if (lvls == null) return List.of();
          Map<Double, Long> agg = new HashMap<>();
          Map<Double, Integer> ord = new HashMap<>();
          int limit = Math.min(5, lvls.size());
        for (int i = 0; i < limit; i++) {
            DepthView.Level L = lvls.get(i);
            if (L == null) continue;
            double px = L.getPrice();
            int q = L.getQuantity();
            if (!(px > 0.0) || !(q > 0)) continue;
            agg.put(px, agg.getOrDefault(px, 0L) + q);
            ord.put(px, ord.getOrDefault(px, 0) + Math.max(0, L.getOrders()));
          }
          List<Level> out = agg.entrySet().stream().map(e -> new Level(e.getKey(), e.getValue(), ord.getOrDefault(e.getKey(), 0)))
                  .collect(Collectors.toList());
          if (bids) out.sort((a, b) -> Double.compare(b.price, a.price));
          else out.sort(Comparator.comparingDouble(l -> l.price));
          return out;
      }

      private static MaxOrder maxOrder(List<Level> levels) {
          if (levels == null || levels.isEmpty()) return null;
          Level best = null;
          for (Level l : levels) {
              if (l == null || l.qty <= 0) continue;
              if (best == null || l.qty > best.qty) best = l;
          }
          if (best == null) return null;
          return new MaxOrder(best.qty, best.orders, best.price);
      }

    private static double inferTick(Double refPrice) {
        if (refPrice == null || !isFinite(refPrice)) return 0.05; // safe default
        return refPrice < 250.0 ? 0.01 : 0.05;
    }

    private static String inferStage(double ltp, double L, double U, double tick) {
        if (isFinite(ltp) && isFinite(L) && isFinite(U)) {
            double eps = Math.max(tick * 2.0, 1e-3);
            if (almostEq(ltp, L, eps) || almostEq(ltp, U, eps)) return "ESM-2";
        }
        return "ESM-1";
    }

    private static double inferBandP(double L, double U, double base) {
        if (isFinite(L) && isFinite(U)) {
            double ref = (isFinite(base) && base > 0) ? base : ((L + U) / 2.0);
            if (ref > 0) {
                double width = (U - L) / ref;
                return width > 0 ? width : 0.02;
            }
        }
        return 0.02; // fallback when band unknown
    }

    private static long defaultQref(long ltq, long totalVisible, List<Level> bids, List<Level> asks, double L, double U, double tick, Double B1, Double A1) {
        long base = Math.max(ltq, Math.round(0.001 * Math.max(0L, totalVisible)));
        // Lower clip: shares to reach ~2*tick notional (approx. 1 share minimum)
        double refPx = (B1 != null ? B1 : (A1 != null ? A1 : (isFinite(L) && isFinite(U) ? (L + U) / 2.0 : 100.0)));
        long minShares = Math.max(1L, (long)Math.ceil((2.0 * tick * refPx) / Math.max(refPx, 1e-9)));
        // Upper clip: 1% of visible depth to band (both sides sum as conservative cap)
        long vis = bids.stream().filter(l -> l.price >= L).mapToLong(l -> l.qty).sum() + asks.stream().filter(l -> l.price <= U).mapToLong(l -> l.qty).sum();
        long maxShares = Math.max(1L, (long)Math.floor(0.01 * Math.max(1L, vis)));
        long clipped = Math.max(minShares, Math.min(base, maxShares));
        return clipped;
    }

    private static LiquidityImpactResponse.DeltaPoint point(Integer shares, Double price) {
        LiquidityImpactResponse.DeltaPoint p = new LiquidityImpactResponse.DeltaPoint();
        p.shares = shares;
        p.price = price;
        return p;
    }

    private static VwapSide vwapSell(List<Level> bids, long Q, double L) {
        long remain = Q;
        double num = 0.0; long used = 0L;
        for (Level l : bids) {
            if (remain <= 0) break;
            long take = Math.min(remain, l.qty);
            num += l.price * take;
            remain -= take;
            used += take;
        }
        if (remain > 0) { // conservative continuation at L
            num += L * remain;
            used += remain;
            remain = 0;
        }
        double vwap = used > 0 ? num / used : 0.0;
        // Terminal best bid after removing Q
        long r = Q;
        int idx = 0;
        while (idx < bids.size() && r > 0) {
            if (r >= bids.get(idx).qty) { r -= bids.get(idx).qty; idx++; }
            else { r = 0; /* partially consume best; new best is same level */ break; }
        }
        double newBest = (idx < bids.size()) ? bids.get(idx).price : L;
        return new VwapSide(vwap, newBest);
    }

    private static VwapSide vwapBuy(List<Level> asks, long Q, double U) {
        long remain = Q;
        double num = 0.0; long used = 0L;
        for (Level l : asks) {
            if (remain <= 0) break;
            long take = Math.min(remain, l.qty);
            num += l.price * take;
            remain -= take;
            used += take;
        }
        if (remain > 0) {
            num += U * remain;
            used += remain;
            remain = 0;
        }
        double vwap = used > 0 ? num / used : 0.0;
        long r = Q;
        int idx = 0;
        while (idx < asks.size() && r > 0) {
            if (r >= asks.get(idx).qty) { r -= asks.get(idx).qty; idx++; }
            else { r = 0; break; }
        }
        double newBest = (idx < asks.size()) ? asks.get(idx).price : U;
        return new VwapSide(vwap, newBest);
    }

    private static String legendFor(double score) {
        if (score < 25.0) return "Low";
        if (score < 60.0) return "Moderate";
        return "High";
    }

    private static String buildNotes(double spread, double tick, String stage, double ioi, boolean partialDepth) {
        StringBuilder sb = new StringBuilder();
        sb.append("spread=").append(round2(spread)).append(", tick=").append(tick).append(", stage=").append(stage);
        sb.append(", IOI=").append(round4(ioi));
        if (partialDepth) sb.append(", partial depth");
        return sb.toString();
    }

    private static String buildHuman(String symbol, Double B1, Double A1, double L, double U, double IOI, long qref,
                                     LiquidityImpactResponse.Deltas deltas, LiquidityImpactResponse.Scores scores,
                                     double spread, double tick, String stage) {
        StringBuilder sb = new StringBuilder();
        sb.append(symbol).append("  | B1/A1: ").append(fmt(B1)).append(" / ").append(fmt(A1))
          .append(" | Band [").append(fmt(L)).append(",").append(fmt(U)).append("] | IOI ").append(round4(IOI))
          .append(" | Q(ref)=").append(qref).append("\n");
        sb.append(" SELL   | 1-tick: ")
          .append(safeQty(deltas.sell.oneTick)).append(" @ ").append(fmtPrice(deltas.sell.oneTick))
          .append("; to-band: ").append(safeQty(deltas.sell.toBand)).append(" @ ").append(fmt(L)).append("\n");
        sb.append(" BUY    | 1-tick: ")
          .append(safeQty(deltas.buy.oneTick)).append(" @ ").append(fmtPrice(deltas.buy.oneTick))
          .append("; to-band: ").append(safeQty(deltas.buy.toBand)).append(" @ ").append(fmt(U)).append("\n");
        sb.append("Scores  | SELL: ").append(round1(scores.sell)).append("  BUY: ").append(round1(scores.buy))
          .append("   (").append(scores.legend).append(")\n");
        sb.append("Notes   | spread=").append(round2(spread)).append(", tick=").append(tick).append(", stage=").append(stage);
        return sb.toString();
    }

    private static double timeScore(Double sec) {
        if (sec == null) return 0.0;
        if (sec <= 15.0) return 1.0;
        if (sec >= 180.0) return 0.0;
        return clamp01((180.0 - sec) / 165.0);
    }


    // Auction utilities
    private static class AuctionContext {
        List<Double> grid; Map<Double, Long> D; Map<Double, Long> S; double p0; double L; double U;
        AuctionContext(List<Double> grid, Map<Double, Long> d, Map<Double, Long> s, double p0, double L, double U){ this.grid = grid; this.D = d; this.S = s; this.p0 = p0; this.L=L; this.U=U; }
        long maxGapDminusSAbove(double pStar){
            long max = Long.MIN_VALUE; boolean any=false;
            for (Double p : grid) { if (p > pStar && p >= L && p <= U) { long g = D.getOrDefault(p,0L) - S.getOrDefault(p,0L); if (g>max){max=g;} any=true; } }
            return any ? Math.max(0L, max) : 0L;
        }
        long maxGapSminusDFrom(double pStar){
            long max = Long.MIN_VALUE; boolean any=false;
            for (Double p : grid) { if (p >= pStar && p >= L && p <= U) { long g = S.getOrDefault(p,0L) - D.getOrDefault(p,0L); if (g>max){max=g;} any=true; } }
            return any ? Math.max(0L, max) : 0L;
        }
    }

    private static AuctionContext buildAuction(List<Level> bids, List<Level> asks, double L, double U) {
        Set<Double> gridSet = new HashSet<>();
        for (Level b : bids) gridSet.add(b.price);
        for (Level a : asks) gridSet.add(a.price);
        if (isFinite(L)) gridSet.add(L);
        if (isFinite(U)) gridSet.add(U);
        List<Double> grid = new ArrayList<>(gridSet);
        grid.sort(Double::compareTo);
        Map<Double, Long> D = new HashMap<>();
        Map<Double, Long> S = new HashMap<>();
        for (Double p : grid) {
            long d = 0, s = 0;
            for (Level b : bids) if (b.price >= p) d += b.qty;
            for (Level a : asks) if (a.price <= p) s += a.qty;
            D.put(p, d); S.put(p, s);
        }
        // Baseline clearing p0 = highest p in [L,U] with D>=S; fallback to L/U
        Double p0 = null; long lastDiff = Long.MIN_VALUE;
        for (Double p : grid) {
            if (!(p >= L && p <= U)) continue;
            long diff = D.get(p) - S.get(p);
            lastDiff = diff;
            if (diff >= 0) p0 = p; // keep highest
        }
        if (p0 == null) {
            p0 = (lastDiff < 0) ? L : U;
        }
        return new AuctionContext(grid, D, S, p0, L, U);
    }

    private static String safe(String s) { return s != null ? s : "-"; }
    private static boolean isFinite(double x){ return !Double.isNaN(x) && !Double.isInfinite(x); }
    private static double clamp(double v, double lo, double hi){ if (isFinite(lo) && v < lo) return lo; if (isFinite(hi) && v > hi) return hi; return v; }
    private static boolean almostEq(double a, double b, double eps){ return Math.abs(a-b) <= eps; }
    private static double round2(double x){ return Math.round(x * 100.0) / 100.0; }
    private static double round1(double x){ return Math.round(x * 10.0) / 10.0; }
    private static double round4(double x){ return Math.round(x * 10000.0) / 10000.0; }
    private static double clamp01(double x){ if (x<0) return 0; if (x>1) return 1; return x; }
    private static String fmt(Double v){ return v==null?"-":String.format(java.util.Locale.ROOT, "%.2f", v); }
    private static String fmt(double v){ return String.format(java.util.Locale.ROOT, "%.2f", v); }
    private static String fmtPrice(LiquidityImpactResponse.DeltaPoint p){ return p==null||p.price==null?"-":fmt(p.price); }
    private static int safeQty(LiquidityImpactResponse.DeltaPoint p){ return p==null||p.shares==null?0:p.shares; }
}
