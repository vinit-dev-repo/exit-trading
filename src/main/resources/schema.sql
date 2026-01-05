-- =================================================================================================
-- ExitTrading Production Schema (PostgreSQL)
-- Includes: Operational Tables, Raw Market Data, Normalized Fundamentals
-- =================================================================================================

CREATE SCHEMA IF NOT EXISTS trading;
SET search_path TO trading, public;

-- 1. Operational Schema
-- -------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS users (
    user_id         BIGSERIAL PRIMARY KEY,
    username        TEXT NOT NULL UNIQUE,
    display_name    TEXT NOT NULL,
    logging_enabled BOOLEAN DEFAULT TRUE,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_holdings (
    user_id         BIGINT REFERENCES users(user_id),
    tradingsymbol   TEXT NOT NULL,
    PRIMARY KEY (user_id, tradingsymbol)
);

CREATE TABLE IF NOT EXISTS kite_sessions (
    session_id      TEXT PRIMARY KEY,
    user_id         BIGINT REFERENCES users(user_id),
    api_key         TEXT NOT NULL,
    access_token    TEXT,
    public_token    TEXT,
    login_time      TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    is_active       BOOLEAN DEFAULT TRUE,
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS trading_schedules (
    schedule_id     BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(user_id),
    symbol          TEXT NOT NULL,
    instrument_token TEXT NOT NULL,
    side            TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity        INTEGER NOT NULL,
    status          TEXT DEFAULT 'DRAFT',
    trade_date      DATE NOT NULL,
    session_slot    TEXT NOT NULL,
    next_execution  TIMESTAMPTZ,
    limit_price     DOUBLE PRECISION,
    auto_repeat     BOOLEAN DEFAULT FALSE,
    cancel_open_orders BOOLEAN DEFAULT TRUE,
    last_executed_at TIMESTAMPTZ,
    last_message    TEXT,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS logging_scrips (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(user_id),
    exchange        TEXT NOT NULL,
    tradingsymbol   TEXT NOT NULL,
    instrument_token TEXT,
    active          BOOLEAN DEFAULT TRUE,
    added_at        TIMESTAMPTZ DEFAULT NOW(),
    last_logged_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS screener_presets (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(user_id),
    name            TEXT NOT NULL,
    config_json     TEXT NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_screener_presets_user_name
    ON screener_presets (user_id, lower(name));

CREATE TABLE IF NOT EXISTS execution_logs (
    id              BIGSERIAL PRIMARY KEY,
    schedule_id     BIGINT REFERENCES trading_schedules(schedule_id),
    timestamp       TIMESTAMPTZ NOT NULL,
    level           TEXT NOT NULL,
    message         TEXT NOT NULL,
    detail          TEXT
);

CREATE TABLE IF NOT EXISTS depth_snapshots (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT REFERENCES users(user_id),
    tradingsymbol   TEXT NOT NULL,
    ltp             DOUBLE PRECISION,
    open            DOUBLE PRECISION,
    high            DOUBLE PRECISION,
    low             DOUBLE PRECISION,
    prev_close      DOUBLE PRECISION,
    volume          BIGINT,
    avg_price       DOUBLE PRECISION,
    lower_circuit   DOUBLE PRECISION,
    upper_circuit   DOUBLE PRECISION,
    captured_at     TIMESTAMPTZ,
    last_trade_time TEXT,
    buy_quantity    BIGINT,
    sell_quantity   BIGINT,
    last_traded_quantity BIGINT,
    buy_levels      TEXT,
    sell_levels     TEXT
);

CREATE TABLE IF NOT EXISTS esm_monitor_states (
    id              BIGSERIAL PRIMARY KEY,
    schedule_id     BIGINT NOT NULL REFERENCES trading_schedules(schedule_id),
    state           TEXT NOT NULL,
    order_id        TEXT,
    trade_id        UUID,
    reason          TEXT,
    next_action_at  TIMESTAMPTZ,
    active          BOOLEAN DEFAULT TRUE,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS executed_trades (
    trade_id        UUID PRIMARY KEY,
    schedule_id     BIGINT REFERENCES trading_schedules(schedule_id),
    user_id         BIGINT REFERENCES users(user_id),
    symbol          TEXT NOT NULL,
    side            TEXT NOT NULL,
    filled_qty      INTEGER,
    filled_price    DOUBLE PRECISION,
    exchange_order_id TEXT,
    execution_time  TIMESTAMPTZ DEFAULT NOW(),
    status          TEXT,
    remarks         TEXT
);

-- 2. Reference Data
-- -------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS instruments (
    token           BIGINT PRIMARY KEY,
    symbol          TEXT NOT NULL,
    exchange        TEXT DEFAULT 'NSE',
    name            TEXT,
    industry        TEXT,
    face_value      DOUBLE PRECISION,
    source_url      TEXT,
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- 3. Fundamental Data (Normalized & Partitioned)
--    Note: Partitioning syntax requires declarative partitioning on the parent table.
-- -------------------------------------------------------------------------------------------------

-- A. Daily Quotes
CREATE TABLE IF NOT EXISTS daily_quotes (
    report_date     DATE NOT NULL,
    token           BIGINT,
    symbol          TEXT,
    current_price   DOUBLE PRECISION,
    high_price      DOUBLE PRECISION,
    low_price       DOUBLE PRECISION,
    volume          BIGINT,
    market_cap      DOUBLE PRECISION,
    CONSTRAINT pk_daily_quotes PRIMARY KEY (report_date, symbol)
) PARTITION BY RANGE (report_date);

-- B. Daily Valuations
CREATE TABLE IF NOT EXISTS daily_valuations (
    report_date     DATE NOT NULL,
    symbol          TEXT,
    pe_ratio        DOUBLE PRECISION,
    book_value      DOUBLE PRECISION,
    dividend_yield  DOUBLE PRECISION,
    roce            DOUBLE PRECISION,
    roe             DOUBLE PRECISION,
    debt_to_equity  DOUBLE PRECISION,
    piotroski_score DOUBLE PRECISION,
    qtr_sales_var   DOUBLE PRECISION,
    qtr_profit_var  DOUBLE PRECISION,
    CONSTRAINT pk_daily_valuations PRIMARY KEY (report_date, symbol)
) PARTITION BY RANGE (report_date);

-- C. Shareholding Patterns
CREATE TABLE IF NOT EXISTS shareholding_patterns (
    report_date     DATE NOT NULL,
    symbol          TEXT,
    promoter_pct    DOUBLE PRECISION,
    public_pct      DOUBLE PRECISION,
    pledged_pct     DOUBLE PRECISION,
    change_prom_hold DOUBLE PRECISION,
    CONSTRAINT pk_shareholding PRIMARY KEY (report_date, symbol)
) PARTITION BY RANGE (report_date);

-- D. Technical Indicators
CREATE TABLE IF NOT EXISTS technical_indicators (
    report_date     DATE NOT NULL,
    symbol          TEXT,
    dma_50          DOUBLE PRECISION,
    dma_200         DOUBLE PRECISION,
    avg_vol_1wk     BIGINT,
    avg_vol_1mth    BIGINT,
    return_1d       DOUBLE PRECISION,
    return_1wk      DOUBLE PRECISION,
    CONSTRAINT pk_technicals PRIMARY KEY (report_date, symbol)
) PARTITION BY RANGE (report_date);

-- Create Partitions (2024, 2025, 2026)
-- Having these pre-created means you don't need to do anything for the next few years.

-- 2025 (Current)
CREATE TABLE IF NOT EXISTS daily_quotes_y2025 PARTITION OF daily_quotes FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS daily_valuations_y2025 PARTITION OF daily_valuations FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS shareholding_patterns_y2025 PARTITION OF shareholding_patterns FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS technical_indicators_y2025 PARTITION OF technical_indicators FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');

-- 2026 (Future)
CREATE TABLE IF NOT EXISTS daily_quotes_y2026 PARTITION OF daily_quotes FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS daily_valuations_y2026 PARTITION OF daily_valuations FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS shareholding_patterns_y2026 PARTITION OF shareholding_patterns FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS technical_indicators_y2026 PARTITION OF technical_indicators FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

-- 4. Raw Market Data (Partitioned)
-- -------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS market_snapshots (
    captured_at     TIMESTAMPTZ NOT NULL,
    token           BIGINT NOT NULL,
    symbol          TEXT NOT NULL,
    ltp             DOUBLE PRECISION,
    ltq             BIGINT,
    ltt             TEXT,
    volume          BIGINT,
    open_price      DOUBLE PRECISION,
    high_price      DOUBLE PRECISION,
    low_price       DOUBLE PRECISION,
    prev_close      DOUBLE PRECISION,
    lower_circuit   DOUBLE PRECISION,
    upper_circuit   DOUBLE PRECISION,
    bids            JSONB,
    asks            JSONB,
    CONSTRAINT pk_market_snapshots PRIMARY KEY (captured_at, token)
) PARTITION BY RANGE (captured_at);

-- Create Monthly Partitions for high-frequency data
-- Example: Late 2025 and Early 2026
CREATE TABLE IF NOT EXISTS market_snapshots_2025_11 PARTITION OF market_snapshots FOR VALUES FROM ('2025-11-01') TO ('2025-12-01');
CREATE TABLE IF NOT EXISTS market_snapshots_2025_12 PARTITION OF market_snapshots FOR VALUES FROM ('2025-12-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS market_snapshots_2026_01 PARTITION OF market_snapshots FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE IF NOT EXISTS market_snapshots_2026_02 PARTITION OF market_snapshots FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE IF NOT EXISTS market_snapshots_2026_03 PARTITION OF market_snapshots FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE IF NOT EXISTS market_snapshots_2026_04 PARTITION OF market_snapshots FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE IF NOT EXISTS market_snapshots_2026_05 PARTITION OF market_snapshots FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE IF NOT EXISTS market_snapshots_2026_06 PARTITION OF market_snapshots FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE IF NOT EXISTS market_snapshots_2026_07 PARTITION OF market_snapshots FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE IF NOT EXISTS market_snapshots_2026_08 PARTITION OF market_snapshots FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE IF NOT EXISTS market_snapshots_2026_09 PARTITION OF market_snapshots FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE IF NOT EXISTS market_snapshots_2026_10 PARTITION OF market_snapshots FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE IF NOT EXISTS market_snapshots_2026_11 PARTITION OF market_snapshots FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE IF NOT EXISTS market_snapshots_2026_12 PARTITION OF market_snapshots FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
-- Safety Net
CREATE TABLE IF NOT EXISTS market_snapshots_default PARTITION OF market_snapshots DEFAULT;
-- 2027
CREATE TABLE IF NOT EXISTS daily_quotes_y2027 PARTITION OF daily_quotes FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS daily_valuations_y2027 PARTITION OF daily_valuations FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS shareholding_patterns_y2027 PARTITION OF shareholding_patterns FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS technical_indicators_y2027 PARTITION OF technical_indicators FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS market_snapshots_y2027 PARTITION OF market_snapshots FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');

-- 2028
CREATE TABLE IF NOT EXISTS daily_quotes_y2028 PARTITION OF daily_quotes FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');
CREATE TABLE IF NOT EXISTS daily_valuations_y2028 PARTITION OF daily_valuations FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');
CREATE TABLE IF NOT EXISTS shareholding_patterns_y2028 PARTITION OF shareholding_patterns FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');
CREATE TABLE IF NOT EXISTS technical_indicators_y2028 PARTITION OF technical_indicators FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');
CREATE TABLE IF NOT EXISTS market_snapshots_y2028 PARTITION OF market_snapshots FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');

-- 2029
CREATE TABLE IF NOT EXISTS daily_quotes_y2029 PARTITION OF daily_quotes FOR VALUES FROM ('2029-01-01') TO ('2030-01-01');
CREATE TABLE IF NOT EXISTS daily_valuations_y2029 PARTITION OF daily_valuations FOR VALUES FROM ('2029-01-01') TO ('2030-01-01');
CREATE TABLE IF NOT EXISTS shareholding_patterns_y2029 PARTITION OF shareholding_patterns FOR VALUES FROM ('2029-01-01') TO ('2030-01-01');
CREATE TABLE IF NOT EXISTS technical_indicators_y2029 PARTITION OF technical_indicators FOR VALUES FROM ('2029-01-01') TO ('2030-01-01');
CREATE TABLE IF NOT EXISTS market_snapshots_y2029 PARTITION OF market_snapshots FOR VALUES FROM ('2029-01-01') TO ('2030-01-01');

-- 2030
CREATE TABLE IF NOT EXISTS daily_quotes_y2030 PARTITION OF daily_quotes FOR VALUES FROM ('2030-01-01') TO ('2031-01-01');
CREATE TABLE IF NOT EXISTS daily_valuations_y2030 PARTITION OF daily_valuations FOR VALUES FROM ('2030-01-01') TO ('2031-01-01');
CREATE TABLE IF NOT EXISTS shareholding_patterns_y2030 PARTITION OF shareholding_patterns FOR VALUES FROM ('2030-01-01') TO ('2031-01-01');
CREATE TABLE IF NOT EXISTS technical_indicators_y2030 PARTITION OF technical_indicators FOR VALUES FROM ('2030-01-01') TO ('2031-01-01');
CREATE TABLE IF NOT EXISTS market_snapshots_y2030 PARTITION OF market_snapshots FOR VALUES FROM ('2030-01-01') TO ('2031-01-01');
