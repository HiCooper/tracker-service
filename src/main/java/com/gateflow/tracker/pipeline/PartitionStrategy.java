package com.gateflow.tracker.pipeline;

/**
 * Kafka 分区策略
 * 使用 userId hash % partitionCount 保证：
 * 1. 同一用户的事件在同一个分区（保证顺序）
 * 2. 负载均衡分布
 */
public final class PartitionStrategy {

    private PartitionStrategy() {
    }

    /**
     * 计算分区号
     */
    public static int calculatePartition(String key, int partitionCount) {
        if (key == null || key.isEmpty()) {
            return 0;
        }
        return (key.hashCode() & Integer.MAX_VALUE) % partitionCount;
    }

    /**
     * 计算分区号（基于事件）
     */
    public static int calculatePartition(String userId, String anonymousId, String sessionId, int partitionCount) {
        String partitionKey = userId != null ? userId :
                             anonymousId != null ? anonymousId :
                             sessionId != null ? sessionId : "unknown";
        return calculatePartition(partitionKey, partitionCount);
    }
}
