1) Screener Query: Short Swing / Momentum

Idea: You want stocks already showing strength + liquidity, with basic risk controls (pledge/debt).

Recommended thresholds for your ESM-ish universe:
- avg_vol_1mth ≥ 50,000 (fast in/out for swings)
- price > DMA50 and DMA50 > DMA200 (trend alignment)
- return_1wk ≥ +2% (direction confirmation)
- pledged = 0, debt_to_equity ≤ 0.8 (avoid fragile names)

Why these values work for swing
- Liquidity filter prevents “can’t exit” traps.
- DMA alignment avoids long downtrends.
- Weekly return ensures you’re not buying dead bounces.
*/
WITH latest_quotes AS (
  SELECT DISTINCT ON (symbol)
         report_date, token, symbol,
         current_price, high_price, low_price, volume, market_cap
  FROM trading.daily_quotes
  WHERE current_price IS NOT NULL
  ORDER BY symbol, report_date DESC
),
latest_vals AS (
  SELECT DISTINCT ON (symbol)
         report_date, symbol,
         pe_ratio, book_value, dividend_yield, roce, roe,
         debt_to_equity, piotroski_score, qtr_sales_var, qtr_profit_var
  FROM trading.daily_valuations
  ORDER BY symbol, report_date DESC
),
latest_sh AS (
  SELECT DISTINCT ON (symbol)
         report_date, symbol,
         promoter_pct, public_pct, pledged_pct, change_prom_hold
  FROM trading.shareholding_patterns
  ORDER BY symbol, report_date DESC
),
latest_tech AS (
  SELECT DISTINCT ON (symbol)
         report_date, symbol,
         dma_50, dma_200, avg_vol_1wk, avg_vol_1mth,
         return_1d, return_1wk
  FROM trading.technical_indicators
  ORDER BY symbol, report_date DESC
),
base AS (
  SELECT
    i.exchange, i.symbol, i.industry, i.source_url,
    q.report_date AS q_date,
    q.current_price, q.high_price, q.low_price, q.volume, q.market_cap,
    v.pe_ratio, v.roe, v.roce, v.debt_to_equity, v.piotroski_score,
    v.qtr_sales_var, v.qtr_profit_var,
    sh.promoter_pct, sh.public_pct, sh.pledged_pct, sh.change_prom_hold,
    t.dma_50, t.dma_200, t.avg_vol_1wk, t.avg_vol_1mth, t.return_1d, t.return_1wk,
    ( (q.current_price / NULLIF(q.low_price, 0)) - 1 ) * 100.0 AS up_from_low_pct,
    ( 1 - (q.current_price / NULLIF(q.high_price, 0)) ) * 100.0 AS down_from_high_pct,
    (q.current_price > t.dma_50)  AS above_dma50,
    (q.current_price > t.dma_200) AS above_dma200,
    (t.dma_50 > t.dma_200)        AS dma50_above_dma200
  FROM trading.instruments i
  JOIN latest_quotes q ON q.symbol = i.symbol
  LEFT JOIN latest_vals  v  ON v.symbol  = i.symbol
  LEFT JOIN latest_sh    sh ON sh.symbol = i.symbol
  LEFT JOIN latest_tech  t  ON t.symbol  = i.symbol
) 
SELECT 
  exchange, symbol, industry, --source_url,
  q_date,
  current_price, market_cap, volume,
  avg_vol_1mth, avg_vol_1wk,
  return_1d, return_1wk,
  dma_50, dma_200,
  above_dma50, above_dma200, dma50_above_dma200,
  down_from_high_pct, up_from_low_pct,
  pledged_pct, promoter_pct, change_prom_hold,
  debt_to_equity, piotroski_score, roe, roce,
  qtr_sales_var, qtr_profit_var
FROM base
WHERE
  -- Tradability
  avg_vol_1mth >= 50000
  AND market_cap BETWEEN 50 AND 1500

  -- Risk hygiene
  AND COALESCE(pledged_pct, 0) = 0
  AND COALESCE(debt_to_equity, 999) <= 0.8

  -- Swing structure
  AND above_dma50
  AND dma50_above_dma200
  AND COALESCE(return_1wk, -999) >= 2

ORDER BY
  -- Rank: momentum + liquidity
  return_1wk DESC NULLS LAST,
  (volume::numeric / NULLIF(avg_vol_1mth, 0)) DESC NULLS LAST,
  market_cap DESC NULLS LAST;