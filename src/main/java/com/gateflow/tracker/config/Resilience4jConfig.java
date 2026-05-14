package com.gateflow.tracker.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@Slf4j
public class Resilience4jConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(CircuitBreakerProperties properties) {
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                    // 失败率阈值 50%
                .waitDurationInOpenState(Duration.ofSeconds(30))  // 打开状态等待 30 秒
                .slidingWindowSize(10)                      // 滑动窗口大小 10 次
                .minimumNumberOfCalls(5)                   // 最小调用次数
                .permittedNumberOfCallsInHalfOpenState(3)   // 半开状态允许 3 次调用
                .automaticTransitionFromOpenToHalfOpenEnabled(true)  // 自动从打开转为半开
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(defaultConfig);

        // 注册 clickhouse circuit breaker
        CircuitBreaker clickhouseCircuitBreaker = registry.circuitBreaker("clickhouse");
        clickhouseCircuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.info("Circuit breaker state transition: {} -> {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()))
                .onFailureRateExceeded(event -> log.warn("Circuit breaker failure rate exceeded: {}",
                        event.getFailureRate()))
                .onError(event -> log.debug("Circuit breaker recorded error: {}",
                        event.getThrowable().getMessage()));

        return registry;
    }
}
