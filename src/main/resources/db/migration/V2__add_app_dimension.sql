-- 给事件增加 app 维度,统一「契约 key(appCode)= 采集 clientId」的身份,
-- 解锁按 app 的告警/分析。追加为末列(与定位无关的列顺序),幂等。
ALTER TABLE gateflow_tracker.events
    ADD COLUMN IF NOT EXISTS app_code String DEFAULT '';

-- 便于按 app 过滤的跳数索引
ALTER TABLE gateflow_tracker.events
    ADD INDEX IF NOT EXISTS idx_app_code app_code TYPE bloom_filter(0.01) GRANULARITY 4;
