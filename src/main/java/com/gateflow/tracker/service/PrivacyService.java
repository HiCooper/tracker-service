package com.gateflow.tracker.service;

import com.gateflow.tracker.api.dto.EventDTO;
import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.metrics.PipelineMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 隐私合规:在入口处对事件做同意门控与 PII 掩码/哈希(隐私治理)。
 *
 * <p>默认全部关闭(非破坏)。开启后:
 * <ul>
 *   <li>require-consent + consent=false → 剥离 userId 与 PII 字段(仅留匿名维度);</li>
 *   <li>对配置的 PII 字段与(可选)userId 做不可逆哈希;</li>
 *   <li>可选对自定义属性做邮箱/手机号启发式掩码。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrivacyService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?\\d[\\d\\s-]{6,}$");

    private final TrackerProperties properties;
    private final PipelineMetrics metrics;

    public void apply(EventDTO event) {
        if (event == null) {
            return;
        }
        TrackerProperties.Privacy cfg = properties.getPrivacy();
        Map<String, Object> custom = event.getData() != null ? event.getData().getCustom() : null;

        // 1) 同意门控:未授权则剥离 PII
        if (cfg.isRequireConsent() && Boolean.FALSE.equals(event.getConsent())) {
            event.setUserId(null);
            if (custom != null) {
                cfg.getPiiFields().forEach(custom::remove);
            }
            metrics.incrementConsentDenied();
            return;
        }

        // 2) PII 掩码/哈希
        boolean masked = false;
        if (cfg.isHashUserId() && event.getUserId() != null && !event.getUserId().isBlank()) {
            event.setUserId(hash(event.getUserId()));
            masked = true;
        }
        if (custom != null) {
            for (String field : cfg.getPiiFields()) {
                Object v = custom.get(field);
                if (v != null) {
                    custom.put(field, hash(String.valueOf(v)));
                    masked = true;
                }
            }
            if (cfg.isMaskHeuristics()) {
                masked |= maskHeuristics(custom);
            }
        }
        if (masked) {
            metrics.incrementPiiMasked();
        }
    }

    private boolean maskHeuristics(Map<String, Object> custom) {
        boolean changed = false;
        for (Map.Entry<String, Object> e : custom.entrySet()) {
            if (!(e.getValue() instanceof String s)) {
                continue;
            }
            if (EMAIL.matcher(s).matches()) {
                e.setValue(maskEmail(s));
                changed = true;
            } else if (PHONE.matcher(s).matches()) {
                e.setValue(maskPhone(s));
                changed = true;
            }
        }
        return changed;
    }

    /** 邮箱:保留首字符与域名,如 a***@example.com。 */
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String head = local.substring(0, 1);
        return head + "***" + domain;
    }

    /** 手机号:仅保留后 4 位,如 ***1234。 */
    static String maskPhone(String phone) {
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return "***";
        }
        return "***" + digits.substring(digits.length() - 4);
    }

    /** SHA-256 不可逆哈希(取前 16 位 hex,带前缀标识)。 */
    static String hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("h:");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "h:masked";
        }
    }
}
