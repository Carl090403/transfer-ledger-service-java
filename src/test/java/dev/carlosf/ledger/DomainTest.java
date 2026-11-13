package dev.carlosf.ledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.carlosf.ledger.Domain.ValidationException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class DomainTest {

    /** Runs every case of the language-neutral contract shared with the Python service. */
    @Test
    @SuppressWarnings("unchecked")
    public void amountContractCases() throws Exception {
        Map<String, Object> contract = (Map<String, Object>) Json.parse(
                Files.readString(Path.of("contract", "validation_cases.json")));
        List<Object> cases = (List<Object>) contract.get("amounts");
        assertTrue(cases.size() >= 10);
        for (Object o : cases) {
            Map<String, Object> c = (Map<String, Object>) o;
            Object raw = c.get("raw");
            if (c.containsKey("cents")) {
                long expected = ((BigDecimal) c.get("cents")).longValueExact();
                assertEquals("raw=" + raw, expected, Domain.parseAmount(raw));
            } else {
                try {
                    Domain.parseAmount(raw);
                    fail("expected a validation error for raw=" + raw);
                } catch (ValidationException e) {
                    assertEquals("raw=" + raw, List.of(c.get("error")), e.problems());
                }
            }
        }
    }

    @Test
    public void validTransferIsParsed() {
        Domain.TransferRequest r = Domain.validateTransfer(Map.of(
                "source", "CHQ-000001", "destination", "SAV-000002", "amount", "25.00", "memo", "rent"));
        assertEquals(2500, r.amountCents());
        assertEquals("rent", r.memo());
    }

    @Test
    public void memoDefaultsToEmpty() {
        Domain.TransferRequest r = Domain.validateTransfer(Map.of(
                "source", "CHQ-000001", "destination", "SAV-000002", "amount", "1"));
        assertEquals("", r.memo());
    }

    @Test
    public void everyProblemIsReportedAtOnce() {
        Map<String, Object> body = new HashMap<>();
        body.put("source", "bad");
        body.put("destination", "bad");
        body.put("amount", "abc");
        body.put("memo", "x".repeat(141));
        try {
            Domain.validateTransfer(body);
            fail("expected ValidationException");
        } catch (ValidationException e) {
            assertEquals(List.of(
                    "source must look like ABC-123456",
                    "destination must look like ABC-123456",
                    "source and destination must differ",
                    "memo must be a string of at most 140 characters",
                    "amount is not a valid decimal number"), e.problems());
        }
    }

    @Test
    public void sameSourceAndDestinationIsRejected() {
        try {
            Domain.validateTransfer(Map.of("source", "CHQ-000001", "destination", "CHQ-000001", "amount", "1"));
            fail();
        } catch (ValidationException e) {
            assertEquals(List.of("source and destination must differ"), e.problems());
        }
    }

    @Test(expected = ValidationException.class)
    public void bodyMustBeAnObject() {
        Domain.validateTransfer(List.of(1, 2));
    }

    @Test
    public void formatsCents() {
        assertEquals("0.05", Domain.formatCents(5));
        assertEquals("1250.00", Domain.formatCents(125000));
    }
}
