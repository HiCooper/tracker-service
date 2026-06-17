package com.gateflow.tracker.validation;

import com.gateflow.tracker.api.dto.EventDTO;
import com.gateflow.tracker.model.AppSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 按 app 契约校验单个事件,返回违规说明列表(空 = 合规)。
 *
 * <p>校验:未登记的事件类型(schema 漂移)、缺失必填字段、字段类型不符。
 * 校验对象为事件自定义属性 {@code data.custom}。
 */
@Component
public class EventValidator {

    public List<String> validate(EventDTO event, AppSchema schema) {
        if (schema == null || event == null) {
            return Collections.emptyList();
        }
        String eventType = event.getEventType();
        if (!schema.knowsEvent(eventType)) {
            return List.of("unknown event type (schema drift): " + eventType);
        }
        AppSchema.EventSchema es = schema.eventSchema(eventType);
        if (es == null || es.getFields() == null || es.getFields().isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Object> custom = event.getData() != null ? event.getData().getCustom() : null;
        List<String> violations = new ArrayList<>();
        for (AppSchema.FieldSpec field : es.getFields()) {
            Object value = custom != null ? custom.get(field.getName()) : null;
            if (value == null) {
                if (field.isRequired()) {
                    violations.add("missing required field: " + field.getName());
                }
                continue;
            }
            if (!typeMatches(field.getType(), value)) {
                violations.add("field '" + field.getName() + "' expected " + field.getType()
                        + " but got " + value.getClass().getSimpleName());
            }
        }
        return violations;
    }

    private boolean typeMatches(String type, Object value) {
        if (type == null) {
            return true; // 未声明类型则不校验
        }
        return switch (type.toLowerCase()) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Integer || value instanceof Long
                    || (value instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue()));
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List || value instanceof Object[];
            default -> true; // 未知类型不阻塞
        };
    }
}
