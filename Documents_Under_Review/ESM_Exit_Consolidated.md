# ESM Stage 2 Exit - Consolidated Implementation Plan (Phased)
_Last updated: 2025-12-28_

## 0) Scope and objectives
This is the single source of truth that consolidates `PlanningAlgorithm.md` and `ESM_Exit_Implementation_Guide.md`. It covers platform readiness, market-depth ingestion, indicator derivation, and the PCAS exit execution workflow in phases.

Primary objectives:
- Exit only when downside is expected (loss-avoidant).
- Do not auto-place into an upper-circuit climb, but allow early exits just before UC is expected to break (any session).
- Use limit orders only; no market orders in PCAS.
- Persist market depth only when bid/ask levels change.

## 1) User requirements (authoritative)
- Exit only if price is expected to go down.
- Avoid early executions when downside is not expected (cancel or avoid orders).
- Persist market depth only if any bid or ask level changes.
- Allow early exits when UC is expected to break (any session).
- Downside gate uses session trend (TrendDelta/SessionDrift), not P_prev_close.
- P_map = LC and margin = 0.
- If UC is missing and Open_0930 > P_prev_close, set UC = Open_0930.
- If UC is missing and Open_0930 <= P_prev_close, treat UC as unknown and skip UC-based triggers.

---

## Phase 0 - Platform and data readiness (P0/P1/P2)

### P0: Immediate blockers (production)
- Partition cliff: add DEFAULT partition and pre-create monthly partitions to avoid insert failures.
- Debounce flood: persist only when debounce passes and depth changes; do not bypass debounce.
- Stream-down fallback: scheduled capture must REST-fetch a small rotating subset when stream cache is stale or empty.
- Subscription explosion: gate startup instrument sync to avoid massive subscriptions.
- Security: remove hardcoded credentials, enable CSRF, lock down actuator.
- Schema management: avoid `ddl-auto: update` and `sql.init: always` in production.

### P1: High-value fixes
- Holdings parsing: parse `EXCH:SYMBOL|...|token` correctly to avoid fallback depth bugs.
- Holdings schema mismatch: reconcile `users.holdings` JSONB vs `user_holdings` table mapping.
- Latest depth query scaling: add per-partition `(token, captured_at DESC)` indexes or use a latest-per-token query.
- UI N+1: reduce per-scrip daily quote queries during frequent refresh.
- Script/document drift: align `scripts/run.sh` and README with Maven dependencies.

### P2: UI/UX and product quality
- Refactor `dashboard.js` into modules and fix malformed ESM markup.
- Avoid HTML injection for news content.

Acceptance criteria:
- Inserts succeed across month boundaries.
- Persist rate is bounded (~1/sec/token) and change-only.
- Stream-down mode still persists reduced samples.
- DB slowdown does not OOM or permanently stall ingestion.
- No persistence occurs when bid/ask depth is unchanged.

---

## Phase 1 - Market depth ingestion and persistence

### 1.1 Change-only persistence
- Persist only when any bid or ask level changes from the last persisted snapshot.
- Ignore timestamp-only changes to reduce write amplification.

### 1.2 Debounce as primary gate
- Persist only when `debouncePass` and `(isSig || isStale)` are true.
- `significant-change-pct = 0.0` means "disable significance", not "always persist".

### 1.3 REST fallback (stream down)
- On scheduled capture, if stream snapshot is null or stale, REST-fetch a small rotating subset (example 2-5 symbols per cycle).
- Refresh `capturedAt` before persistence to avoid accidental updates.

### 1.4 Partitioning and retention
- Add DEFAULT partition for `market_snapshots`.
- Pre-create monthly partitions (current + next 12) and per-partition indexes.
- Define retention and optional downsampling for older partitions.

---

## Phase 2 - Indicator derivation

### 2.1 Required inputs per snapshot
`captured_at`, `symbol`, `ltp`, top-5 `bids` and `asks` (price, qty, orders), `prev_close`, `upper_circuit`, `lower_circuit`, `today_open`.

### 2.2 Derived inputs
- `LC`: lower circuit.
- `P_map`: `LC` (user requirement).
- `P_limit`: execution limit price, alias of `P_map`.
- `margin`: 0 (user requirement).
- `P_prev_close`: previous day close (T-1).
- `Open_0930`: first print in S1.
- `LTP_0930`: LTP at 09:30; if missing, use first available snapshot in S1.
- `UC`:
  - If feed provides UC, use it.
  - If UC missing and `Open_0930 > P_prev_close`, set `UC = Open_0930`.
  - If UC missing and `Open_0930 <= P_prev_close`, treat UC as unknown.
- `L_base`: median of `sum(top-5 bid qty)` for the same session and 5-minute bucket across last N days (default N = 20).

### 2.3 Core metrics
```
I = (sum_bid_qty - sum_ask_qty) / (sum_bid_qty + sum_ask_qty)
```
- Use top-5 levels. If denominator is 0, set `I = 0`.

```
F = (sum of top 3 bid qty) / (sum of top-5 bid qty)
```
- Use as spoof proxy when age data is not available.

```
L = sum(bid_qty at >= P*_robust) / exit_qty
```
- Compute using top-5 bid levels (or full book if available).

### 2.4 Tick size and trend
```
tick_size = min positive price increment across unique top-5 bid/ask prices
tick_size = 0.05 if fewer than 2 unique prices
W = 5 minutes (default)
TrendDelta = median(P*_robust last W minutes) - median(P*_robust previous W minutes)
TrendDelta_tpm = TrendDelta / tick_size / W
TrendThreshold = -0.2 ticks per minute
N_min = 3 snapshots in last W minutes
snapshots_W = count of snapshots in last W minutes (change-only snapshots)
SessionDrift = median(P*_robust current session) - median(P*_robust previous session)
```
Rules:
- Use `TrendDelta_tpm` only when `snapshots_W >= N_min`.
- Use `SessionDrift` only when `snapshots_W < N_min`.
- If previous session data is missing, set `SessionDrift = 0`.

### 2.5 Equilibrium estimation
`P*_raw` (top-5 equilibrium):
```
prices = sorted(unique(bid_prices U ask_prices), reverse=True)
for p in prices:
  cum_buy = sum(qty for bid price >= p)
  cum_sell = sum(qty for ask price <= p)
  tradable = min(cum_buy, cum_sell)
choose p with max tradable
tie-break: min |cum_buy - cum_sell|, then closest to prev_close
```

`P*_robust` (age-weighted, preferred):
- Track `first_seen` for each (side, price, qty) level; reset when qty or price changes, or when the level disappears.
- `age_seconds = now - first_seen`
- `weight = min(1.0, age_seconds / 60)`
- `qty_weighted = qty * weight`
- Run the same equilibrium logic on weighted quantities.

Fallback (no age tracking):
- If `F > 0.8`, remove top `k` buy orders by quantity (`k = 1` or `2`).
- Or cap each order at the 95th percentile of recent per-order size.

Bracketed interpretation:
```
P*_robust <= P_eq <= P*_raw
```

### 2.6 UC states and downside gate
```
UC_lock = abs(LTP - UC) <= tick_size AND abs(P*_robust - UC) <= tick_size
UC_break_expected = abs(LTP - UC) <= tick_size AND P*_robust <= UC - tick_size
                   AND (TrendDelta_tpm <= TrendThreshold OR SessionDrift <= -2 * tick_size)
                   AND I <= -0.1

D_down = (UC_break_expected) OR
         ((TrendDelta_tpm <= TrendThreshold OR SessionDrift <= -2 * tick_size) AND I <= -0.1)
```
Rules:
- If UC is unknown, set `UC_lock = false` and `UC_break_expected = false`.
- If `P*_robust` is undefined (no bids/asks), set `D_down = false`.

---

## Phase 3 - Order entry gating (00:00 to 15:00 of session)

### 3.1 Session 1 guard
- If `LTP_0930 > P_prev_close` and `UC_break_expected` is false, skip S1 and re-evaluate in S2.
- If `LTP_0930` missing, use first snapshot in S1; if none, skip the guard for that session.

### 3.2 Any session gate
- If `D_down` is false or `UC_lock` is true, do nothing (preserve holding).
- If `D_down` is true (or `UC_break_expected` is true), place a limit sell at `P_limit` (LC).
- Rationale: time priority matters only when downside is expected.

---

## Phase 4 - Monitor (15:00 to 43:55)
- If `D_down` flips false after order placement, cancel the order.
- Avoid frequent modifications (resets priority). Modify only when:
  - `P*_robust >= P_limit + (2 * tick_size)`.

---

## Phase 5 - Hazard zone (43:55 to 45:00)
- **Activation**: Starts at `session_start + 44m`.
- **Dynamic Supply Trigger**:
  - Formula: `ThreatLevel = TotalSellQty + MyHoldings + (0.05 * TotalBuyQty)`
  - Rule: If `TotalBuyQty <= ThreatLevel`, execute **Immediate Sell**.
- **Phantom Wall Check**:
  - Rule: If Top-3 Buy Qty drops by **>40%** from rolling max (within session), execute **Immediate Sell**.
- **Conditional Timeout (44m 50s)**:
  - If defensive order is open:
    - Check Safety (`TotalBuyQty > ThreatLevel`).
    - If Safe: **Cancel** order (False Alarm).
    - If Unsafe: Leave order open.

---

## Phase 6 - Post-session (Aggressive Rollover)
- **Session Result Analyzer**:
  - If order failed to fill AND market state was "Unsafe" (High Risk) at expiry:
    - Mark schedule for **Aggressive Rollover**.
- **Sniper Entry**:
  - Schedule next order for `NextSessionStart - 200ms` (Latency Compensation).
  - Aim: Be first in line at the exchange server for the next session open.
- If not unsafe, roll normally to next day.

---

## Phase 7 - Logging and audit
**Structured Tags** introduced for filterability:
- `[MONITOR_START]`: Monitoring active.
- `[ESM_TRIGGER]`: Execution fired (Reason logged).
- `[PHANTOM_WALL]`: Panic exit triggered.
- `[HAZARD_PRESSURE]`: Defensive exit triggered.
- `[HAZARD_SAFE]`: False alarm, order cancelled.
- `[SIGNAL_REVERSAL]`: D_down flipped false, order cancelled.
- `[NO_TRIGGER]`: Session expired safely.

Log per decision (persisted to `executed_logs`):
- `timestamp`, `session_id`, `tag`
- `LC`, `P_map`, `P_limit`, `UC`
- `P*_raw`, `P*_robust`
- `D_down` truth value
- order actions: placed, modified, cancelled, filled

---

## Phase 8 - Validation and tests

### 8.1 DB validation runbook
List leaf partitions:
```sql
SELECT
  t.relid::regclass AS partition,
  pg_get_expr(c.relpartbound, c.oid) AS bound
FROM pg_partition_tree('trading.market_snapshots') t
JOIN pg_class c ON c.oid = t.relid
WHERE t.isleaf
ORDER BY 1;
```

Sanity insert:
```sql
INSERT INTO trading.market_snapshots (captured_at, token, symbol)
VALUES (now(), 999999, 'PARTITION_TEST');

DELETE FROM trading.market_snapshots
WHERE token = 999999 AND symbol = 'PARTITION_TEST';
```

### 8.2 Runtime checks
Rows in last minute:
```sql
SELECT count(*) AS rows_last_minute
FROM trading.market_snapshots
WHERE captured_at > now() - interval '1 minute';
```

Top tokens by write volume:
```sql
SELECT token, count(*)
FROM trading.market_snapshots
WHERE captured_at > now() - interval '5 minutes'
GROUP BY token
ORDER BY count(*) DESC
LIMIT 20;
```

### 8.3 Behavior tests (Unit Test Suite)
Implemented `EsmExecutionServiceTest.java` using `IstClock` mock:
- **Verified**: Phantom Wall logic triggers on 40% drop.
- **Verified**: Hazard Pressure triggers when `Buy <= Sell + Margin`.
- **Verified**: Timeout Cancellation works when risk subsides.
- **Verified**: Sniper logic arms when session expires unsafe.
- **Verified**: D_down logic triggers correct entry.

---

## Appendix A - Partition SQL (market_snapshots)

Default partition:
```sql
CREATE TABLE IF NOT EXISTS market_snapshots_default
  PARTITION OF market_snapshots DEFAULT;
```

Create next N monthly partitions (+ index):
```sql
DO $$
DECLARE
  months_ahead int := 12;
  start_month  date := date_trunc('month', now())::date;
  part_start   date;
  part_end     date;
  part_name    text;
  i            int;
BEGIN
  FOR i IN 0..months_ahead LOOP
    part_start := (start_month + make_interval(months => i))::date;
    part_end   := (start_month + make_interval(months => i + 1))::date;
    part_name  := format('market_snapshots_%s', to_char(part_start, 'YYYY_MM'));

    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS %I PARTITION OF market_snapshots FOR VALUES FROM (%L) TO (%L)',
      part_name, part_start, part_end
    );

    EXECUTE format(
      'CREATE INDEX IF NOT EXISTS %I ON %I (token, captured_at DESC)',
      part_name || '_token_captured_at_idx', part_name
    );
  END LOOP;
END $$;
```

---

## Open decisions (architect)
- Single-tenant vs multi-tenant Kite session.
- Sampling granularity and retention horizon for ML.
- Logging scrips: curated watchlist vs broad universe.
- Degraded mode behavior when stream is down (REST sampling rate, alerting, UI indicators).
