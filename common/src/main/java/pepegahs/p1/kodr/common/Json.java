package pepegahs.p1.kodr.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JavaType;

public final class Json {
    private Json() {
    }

    public static String write(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode JSON", error);
        }
    }

    public static <T> T read(ObjectMapper mapper, String value, Class<T> type) {
        return read(mapper, value, mapper.constructType(type));
    }

    public static <T> T read(ObjectMapper mapper, String value, JavaType type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode JSON", error);
        }
    }
}
