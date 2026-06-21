-- 给会话增加 app 维度,与事件 V2 的 app_code 对齐(appCode = 采集 clientId = 契约 key),
-- 解锁按 app 的会话/留存分析下钻。追加为末列(幂等),与 events 同样用 bloom_filter 跳数索引。
ALTER TABLE gateflow_tracker.sessions
    ADD COLUMN IF NOT EXISTS app_code String DEFAULT '';

ALTER TABLE gateflow_tracker.sessions
    ADD INDEX IF NOT EXISTS idx_sess_app_code app_code TYPE bloom_filter(0.01) GRANULARITY 4;
