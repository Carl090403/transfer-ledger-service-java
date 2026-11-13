package dev.carlosf.ledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class JsonTest {

    @Test
    @SuppressWarnings("unchecked")
    public void parsesNestedValues() {
        Map<String, Object> m = (Map<String, Object>) Json.parse(
                "{\"a\":\"x\\\"y\\u00e9\",\"n\":12.50,\"b\":true,\"z\":null,\"l\":[1,\"two\",{}]}");
        assertEquals("x\"yé", m.get("a"));
        assertEquals(new BigDecimal("12.50"), m.get("n"));
        assertEquals(Boolean.TRUE, m.get("b"));
        assertNull(m.get("z"));
        assertEquals(3, ((List<Object>) m.get("l")).size());
    }

    @Test
    public void rejectsMalformedInput() {
        for (String bad : List.of("", "{", "{\"a\":}", "{\"a\":1,}", "[1 2]", "01", "1.", "tru", "{\"a\":1} x", "\"\u0001\"")) {
            try {
                Json.parse(bad);
                throw new AssertionError("should reject: " + bad);
            } catch (Json.ParseException expected) {
                // ok
            }
        }
    }

    @Test
    public void writesAndRoundTrips() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "line\nbreak \"q\"");
        m.put("n", 42L);
        m.put("l", Arrays.asList("a", null, true));
        String text = Json.write(m);
        assertEquals("{\"s\":\"line\\nbreak \\\"q\\\"\",\"n\":42,\"l\":[\"a\",null,true]}", text);
        assertEquals("line\nbreak \"q\"", ((Map<?, ?>) Json.parse(text)).get("s"));
    }
}
