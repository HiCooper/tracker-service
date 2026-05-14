-- GateFlow Tracker Schema
-- V1__init_tracker_schema.sql

CREATE DATABASE IF NOT EXISTS gateflow_tracker;

-- 事件主表
-- 优化：ORDER BY 以 user_id 开头，提升用户中心化查询性能
-- 添加 TTL 实现 90 天自动数据清理
CREATE TABLE IF NOT EXISTS gateflow_tracker.events (
    event_id       String,
    event_type     String,
    user_id        String,
    anonymous_id   String,
    session_id     String,

    -- 时间字段
    timestamp      DateTime64(3),
    client_time    DateTime64(3),
    received_at    DateTime64(3) DEFAULT now64(3),

    -- 平台信息
    platform       String,
    app_version    String,
    sdk_version    String,

    -- 页面上下文
    page_url       String,
    page_title     String,
    page_referrer  String,

    -- 埋点路径 (a.b.c.d 四级)
    spma           String,
    spmb           String,
    spmc           String,
    spmd           String,

    -- 设备信息
    device_type    String,
    os             String,
    browser        String,
    screen_width   UInt32,
    screen_height  UInt32,
    language       String,

    -- 交互数据
    element_id     String,
    element_type   String,
    element_text   String,
    click_x        Nullable(Int32),
    click_y        Nullable(Int32),
    scroll_depth   Nullable(UInt8),
    stay_duration  Nullable(Int64),

    -- 归因数据
    utm_source     String,
    utm_medium     String,
    utm_campaign   String,
    utm_term       String,
    utm_content    String,

    -- 实验关联（可选）
    exp_ids        Array(String),
    variants       Array(String),

    -- 自定义属性 (JSON)
    properties     String
) ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(timestamp)
ORDER BY (user_id, timestamp, event_type, session_id)
TTL timestamp + toIntervalDay(90)
SETTINGS index_granularity = 8192;

-- 事件表索引
ALTER TABLE gateflow_tracker.events ADD INDEX idx_user_id user_id TYPE bloom_filter(0.01) GRANULARITY 4;
ALTER TABLE events ADD INDEX idx_session session_id TYPE bloom_filter(0.01) GRANULARITY 4;
ALTER TABLE events ADD INDEX idx_event_type event_type TYPE set(100) GRANULARITY 4;
ALTER TABLE events ADD INDEX idx_page_url page_url TYPE bloom_filter(0.01) GRANULARITY 4;
ALTER TABLE events ADD INDEX idx_spmb spmb TYPE bloom_filter(0.01) GRANULARITY 4;
ALTER TABLE events ADD INDEX idx_spmd spmd TYPE bloom_filter(0.01) GRANULARITY 4;

-- 会话表
CREATE TABLE IF NOT EXISTS gateflow_tracker.sessions (
    session_id     String,
    user_id        String,
    anonymous_id   String,
    platform       String,

    start_time     DateTime,
    end_time       Nullable(DateTime),
    duration       Nullable(Int64),

    -- 聚合指标
    page_views     UInt32 DEFAULT 0,
    clicks         UInt32 DEFAULT 0,
    exposures      UInt32 DEFAULT 0,
    scroll_depth_max UInt8 DEFAULT 0,

    -- 会话质量
    is_bounce      UInt8 DEFAULT 0,
    bounce_page    String,

    -- 页面序列
    first_page_url String,
    last_page_url  String,

    -- 归因数据
    utm_source     String,
    utm_medium     String,
    utm_campaign   String,

    -- 设备信息
    device_type    String,
    os             String,

    -- 元数据
    last_active_at DateTime DEFAULT now()

) ENGINE = ReplacingMergeTree(last_active_at)
PARTITION BY toYYYYMMDH(start_time)
ORDER BY (user_id, start_time)
TTL start_time + toIntervalDay(90)
SETTINGS index_granularity = 8192;

-- 小时级事件聚合（用于实时仪表盘）
CREATE MATERIALIZED VIEW IF NOT EXISTS gateflow_tracker.events_hourly
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMMDD(timestamp)
ORDER BY (user_id, spmb, spmd, event_type, timestamp)
AS SELECT
    toStartOfHour(timestamp) as timestamp,
    user_id,
    anonymous_id,
    session_id,
    spmb,
    spmd,
    event_type,
    count() as event_count,
    sumIf(1, event_type = 'click') as click_count,
    sumIf(1, event_type = 'exposure') as exposure_count,
    sumIf(1, event_type = 'page_view') as page_view_count,
    avg(stay_duration) as avg_stay_duration,
    max(scroll_depth) as max_scroll_depth
FROM events
GROUP BY timestamp, user_id, anonymous_id, session_id, spmb, spmd, event_type;

-- 每日会话指标（用于日报表）
CREATE MATERIALIZED VIEW IF NOT EXISTS gateflow_tracker.sessions_daily
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMMDD(start_time)
ORDER BY (user_id, start_time)
AS SELECT
    toDate(start_time) as date,
    user_id,
    anonymous_id,
    platform,
    utm_source,
    utm_medium,
    utm_campaign,
    count() as session_count,
    sum(page_views) as total_page_views,
    sum(clicks) as total_clicks,
    sum(exposures) as total_exposures,
    sum(duration) as total_duration,
    avg(duration) as avg_duration,
    sum(is_bounce) as bounce_count,
    max(scroll_depth_max) as max_scroll_depth
FROM sessions
GROUP BY date, user_id, anonymous_id, platform, utm_source, utm_medium, utm_campaign;
