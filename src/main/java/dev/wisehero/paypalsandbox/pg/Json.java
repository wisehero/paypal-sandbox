package dev.wisehero.paypalsandbox.pg;

import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;

/** PayPal 응답을 Map으로 받아 필요한 값만 꺼내는 도우미. */
final class Json {

    static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    private Json() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> map, String key) {
        return map != null && map.get(key) instanceof List<?> list ? (List<Object>) list : List.of();
    }

    static String string(Map<String, Object> map, String key) {
        return map != null && map.get(key) instanceof String s ? s : null;
    }
}
