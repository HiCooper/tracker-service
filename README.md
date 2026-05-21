# GateFlow Tracker Service

GateFlow Tracker Service 是一个高性能的事件追踪微服务，用于收集、处理和存储用户行为事件数据。该服务基于 Spring Boot 3.4 构建，采用 Kafka 作为消息队列，ClickHouse 作为分析型数据库，支持会话管理、事件去重、限流和死信队列等功能。

## 技术栈

- **Java 17** - 编程语言
- **Spring Boot 3.4** - 应用框架
- **Kafka** - 消息队列，用于事件异步处理
- **ClickHouse** - 列式数据库，用于事件数据存储和分析
- **Redis** - 缓存和会话存储
- **Caffeine** - 本地缓存，用于两级去重
- **Resilience4j** - 熔断器和容错处理
- **Bucket4j** - 令牌桶限流算法实现
- **Docker Compose** - 容器化部署

## 核心功能

### 1. 事件采集
- RESTful API 接收客户端事件上报
- 批量事件处理能力
- 事件数据验证和清洗

### 2. 会话管理
- 自动创建和维护用户会话
- 会话数据存储在 ClickHouse（ReplacingMergeTree）
- 会话超时自动清理（默认 30 分钟）
- 会话聚合指标统计（PV、点击数、曝光数、滚动深度、跳出判断）

### 3. 事件去重
- 两级去重架构：本地 Caffeine 缓存 + Redis SET NX 分布式锁
- 可配置的去重时间窗口（默认 5 分钟）
- 基于事件 ID 的精确去重，Redis 故障时自动降级

### 4. 数据增强
- 自动添加接收时间戳（receivedAt）
- 会话 ID 关联与指标更新
- User-Agent 解析（设备类型、操作系统、浏览器）
- UTM 参数提取

### 5. 限流保护
- 基于 Bucket4j 的令牌桶算法 + Caffeine 缓存管理 bucket 生命周期
- 按客户端 ID 进行限流，bucket 自动过期回收
- 可配置的速率限制（默认 10000 请求/秒，峰值 20000）

### 6. 死信队列（DLQ）
- 自动存储失败的事件
- 定时重试机制
- 可配置的重试次数和 TTL
- 失败原因追踪

### 7. 容错处理
- Resilience4j 熔断器保护 ClickHouse 写入
- 自动故障恢复
- 优雅降级策略

## 项目结构

```
tracker-service/
├── src/main/java/com/gateflow/tracker/
│   ├── api/                    # REST API 层
│   │   ├── dto/               # 数据传输对象
│   │   ├── EventController.java    # 事件采集接口
│   │   └── HealthController.java   # 健康检查接口
│   ├── config/                # 配置类
│   │   ├── CaffeineConfig.java
│   │   ├── ClickHouseProperties.java
│   │   ├── KafkaConsumerConfig.java
│   │   ├── KafkaProducerConfig.java
│   │   ├── RedisConfig.java
│   │   ├── Resilience4jConfig.java
│   │   └── TrackerProperties.java
│   ├── model/                 # 数据模型
│   │   ├── DLQEntry.java
│   │   ├── EventRecord.java
│   │   └── Session.java
│   ├── pipeline/              # 数据处理管道
│   │   ├── ClickHouseKafkaConsumer.java
│   │   ├── ClickHouseWriter.java
│   │   ├── PartitionStrategy.java
│   │   └── TrackerKafkaProducer.java
│   ├── repository/            # 数据访问层
│   │   └── SessionRepository.java
│   ├── scheduler/             # 定时任务
│   │   ├── DLQReplayTask.java      # 死信队列重试任务
│   │   └── SessionCleanupTask.java # 会话清理任务
│   ├── service/               # 业务逻辑层
│   │   ├── DLQService.java
│   │   ├── DeduplicationService.java
│   │   ├── EnrichmentService.java
│   │   ├── EventCollectorService.java
│   │   ├── RateLimiterService.java
│   │   └── SessionService.java
│   ├── util/                  # 工具类
│   │   └── RedisKeyUtils.java
│   └── TrackerServiceApplication.java
├── src/main/resources/
│   ├── db/migration/          # 数据库迁移脚本
│   │   └── V1__init_tracker_schema.sql
│   ├── application.yml        # 主配置文件
│   └── application-dev.yml    # 开发环境配置
├── docker-compose.yml         # Docker Compose 配置
└── pom.xml                    # Maven 配置
```

## 快速开始

### 前置要求

- Java 17 或更高版本
- Maven 3.6+
- Docker & Docker Compose

### 1. 启动依赖服务

使用 Docker Compose 启动 Redis、Kafka 和 ClickHouse：

```bash
docker-compose up -d
```

这将启动以下服务：
- Redis (端口 6379)
- Kafka (端口 9092)
- Zookeeper (Kafka 依赖)
- ClickHouse (HTTP 端口 8123, TCP 端口 9000)

### 2. 构建项目

```bash
mvn clean package -DskipTests
```

### 3. 运行应用

```bash
java -jar target/tracker-service-1.0.0-SNAPSHOT.jar
```

或者使用 Maven 直接运行：

```bash
mvn spring-boot:run
```

应用将在 `http://localhost:8081` 启动。

### 4. 验证服务

检查健康状态：

```bash
curl http://localhost:8081/actuator/health
```

## API 使用示例

### 上报事件

```bash
curl -X POST http://localhost:8081/api/v1/collect \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "web-client-001",
    "events": [
      {
        "eventId": "evt_001",
        "eventType": "page_view",
        "userId": "user_123",
        "anonymousId": "anon_456",
        "timestamp": 1699000000000,
        "session": {
          "sessionId": "sess_abc"
        },
        "page": {
          "url": "https://example.com/home",
          "title": "Home Page"
        },
        "device": {
          "userAgent": "Mozilla/5.0 ...",
          "screenWidth": 1920,
          "screenHeight": 1080,
          "language": "zh-CN"
        },
        "context": {
          "utmSource": "google",
          "utmMedium": "cpc"
        },
        "data": {
          "customField": "value"
        }
      }
    ]
  }'
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "accepted": 1,
    "duplicate": 0,
    "rejected": 0,
    "dlq": 0
  }
}
```

## 配置说明

主要配置项在 `application.yml` 中：

### 服务器配置
```yaml
server:
  port: 8081  # 可通过 TRACKER_PORT 环境变量覆盖
```

### 会话管理
```yaml
tracker:
  session:
    timeout-minutes: 30           # 会话超时时间
    cleanup-interval-ms: 300000   # 清理间隔（5分钟）
    heartbeat-interval-ms: 30000  # 心跳间隔（30秒）
```

### 去重配置
```yaml
tracker:
  dedup:
    window-minutes: 5              # 去重时间窗口
    two-stage-enabled: true        # 启用两级去重
    local-cache-size: 100000       # 本地缓存大小
    local-cache-ttl-seconds: 60    # 本地缓存 TTL
```

### 限流配置
```yaml
tracker:
  rate-limit:
    max-per-second: 10000  # 每秒最大请求数
    burst: 20000           # 峰值容量
```

### 死信队列
```yaml
tracker:
  dlq:
    replay-interval-ms: 60000   # 重试间隔（1分钟）
    max-retry-count: 10         # 最大重试次数
    ttl-days: 7                 # 数据保留天数
```

### Kafka 配置
```yaml
tracker:
  kafka:
    bootstrap-servers: localhost:9092
    topics:
      events: tracker-events
      events-dlq: tracker-events-dlq
      sessions: tracker-sessions
```

### ClickHouse 配置
```yaml
tracker:
  clickhouse:
    url: ${CLICKHOUSE_URL:jdbc:clickhouse://localhost:8123/gateflow_tracker}
    user: ${CLICKHOUSE_USER:default}
    password: ${CLICKHOUSE_PASSWORD:}
```

## 环境变量

可以通过环境变量覆盖配置：

- `TRACKER_PORT` - 服务端口（默认 8081）
- `KAFKA_BOOTSTRAP_SERVERS` - Kafka 地址（默认 localhost:9092）
- `CLICKHOUSE_URL` - ClickHouse JDBC URL
- `CLICKHOUSE_USER` - ClickHouse 用户名
- `CLICKHOUSE_PASSWORD` - ClickHouse 密码
- `REDIS_HOST` - Redis 主机（默认 localhost）
- `REDIS_PORT` - Redis 端口（默认 6379）
- `REDIS_PASSWORD` - Redis 密码

## 监控和可观测性

### Actuator 端点

- 健康检查：`http://localhost:8081/actuator/health`
- 指标：`http://localhost:8081/actuator/metrics`
- Prometheus：`http://localhost:8081/actuator/prometheus`

### 日志

日志级别可在配置文件中调整：

```yaml
logging:
  level:
    com.gateflow.tracker: INFO
    org.springframework.kafka: WARN
```

## 测试

运行单元测试：

```bash
mvn test
```

## 生产部署建议

1. **资源规划**
   - JVM 堆内存：至少 2GB
   - CPU：4 核以上
   - 磁盘：根据事件量规划 ClickHouse 存储

2. **高可用**
   - 多实例部署，配合负载均衡
   - Kafka 集群模式（3 节点以上）
   - ClickHouse 集群配置
   - Redis Sentinel 或 Cluster

3. **安全加固**
   - 启用 Redis 密码认证
   - 配置 ClickHouse 访问控制
   - 使用 HTTPS
   - 配置防火墙规则

4. **性能优化**
   - 调整 Kafka 消费者并发度
   - 优化 ClickHouse 表分区策略
   - 调优 Redis 连接池参数
   - 监控和调整限流阈值

## 故障排查

### 常见问题

1. **ClickHouse 连接失败**
   ```bash
   # 检查 ClickHouse 是否运行
   docker ps | grep clickhouse
   
   # 查看日志
   docker logs tracker-clickhouse
   ```

2. **Kafka 消费延迟**
   - 检查消费者组状态
   - 增加消费者并发度
   - 监控 Kafka lag

3. **Redis 连接超时**
   - 检查 Redis 负载
   - 调整连接池大小
   - 检查网络延迟

## 开发指南

### 添加新的事件类型

1. 在 `EventDTO` 中添加新字段（如需要）
2. 更新 `EnrichmentService` 处理逻辑
3. 修改 ClickHouse 表结构（如需要）
4. 添加相应的测试用例

### 扩展数据处理管道

1. 在 `pipeline` 包中创建新的处理器
2. 在 `EventCollectorService` 中集成新处理器
3. 配置相关的容错和监控

## 许可证

本项目为 GateFlow 系统的一部分。

## 联系方式

如有问题或建议，请联系 GateFlow 团队。
