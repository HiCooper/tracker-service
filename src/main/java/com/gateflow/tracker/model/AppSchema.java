package com.gateflow.tracker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 某个 app 的事件契约(由 tracker-admin 的埋点方案编译、发布到 Redis)。
 *
 * <p>JSON 形如:
 * <pre>
 * {"appId":"app1","version":3,"events":{
 *   "purchase":{"fields":[{"name":"orderId","type":"string","required":true}]},
 *   "page_view":{"fields":[]}}}
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSchema {

    private String appId;
    private long version;
    /** eventType → 该事件的字段契约。 */
    private Map<String, EventSchema> events;

    public EventSchema eventSchema(String eventType) {
        return events != null ? events.get(eventType) : null;
    }

    public boolean knowsEvent(String eventType) {
        return events != null && events.containsKey(eventType);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventSchema {
        private List<FieldSpec> fields;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FieldSpec {
        private String name;
        /** string | number | integer | boolean | object | array */
        private String type;
        private boolean required;
    }
}
