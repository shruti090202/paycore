package com.paycore.risk;

import java.util.Map;

/** A rule row as seen by the engine: effective config for one merchant (override or global default). */
public record RuleConfig(String id, String merchantId, String type, String name, Map<String, Object> params,
                         int weight, boolean enabled) {

    public long paramLong(String key, long defaultValue) {
        Object v = params == null ? null : params.get(key);
        return v instanceof Number n ? n.longValue() : defaultValue;
    }

    public boolean isOverride() {
        return merchantId != null;
    }
}
