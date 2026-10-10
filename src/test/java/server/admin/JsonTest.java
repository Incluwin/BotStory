package server.admin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    void primitiveFieldsSerializeWithoutQuotes() {
        assertEquals("{\"a\":1,\"b\":true,\"c\":null}", Json.obj("a", 1, "b", true, "c", null));
    }

    @Test
    void stringsAreQuotedAndEscaped() {
        assertEquals("{\"msg\":\"hello \\\"world\\\"\"}",
                Json.obj("msg", "hello \"world\""));
    }

    @Test
    void nestedMapSerializes() {
        String out = Json.obj("outer", Map.of("inner", 42));
        // Map ordering isn't guaranteed; just check structure
        assertTrue(out.startsWith("{\"outer\":{"));
        assertTrue(out.contains("\"inner\":42"));
    }

    @Test
    void iterableSerializesAsArray() {
        assertEquals("{\"xs\":[1,2,3]}", Json.obj("xs", List.of(1, 2, 3)));
    }

    @Test
    void rawValueSplicedVerbatim() {
        String nested = Json.obj("a", 1);
        String out = Json.obj("wrapped", Json.raw(nested));
        assertEquals("{\"wrapped\":{\"a\":1}}", out);
    }

    @Test
    void arrOfStringsSerializes() {
        assertEquals("[\"a\",\"b\"]", Json.arr(List.of("a", "b")));
    }

    @Test
    void controlCharactersAreEscaped() {
        assertEquals("{\"s\":\"\\n\\t\\r\"}", Json.obj("s", "\n\t\r"));
    }
}
