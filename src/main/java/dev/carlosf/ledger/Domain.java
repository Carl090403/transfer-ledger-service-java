package dev.carlosf.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Domain rules, free of HTTP and storage concerns. Mirrors ledger/domain.py of the Python service. */
public final class Domain {
    private Domain() {}

    public static final Pattern ACCOUNT_RE = Pattern.compile("^[A-Z]{3}-\\d{6}$");
    private static final Pattern AMOUNT_RE = Pattern.compile("-?\\d+(\\.\\d{1,2})?");
    private static final Pattern TOO_MANY_DECIMALS = Pattern.compile("-?\\d+\\.\\d{3,}");
    public static final long MAX_TRANSFER_CENTS = 1_000_000_00L; // 1,000,000.00 CAD per transfer (demo limit)

    /** Every input problem at once (HTTP 400). */
    public static class ValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient List<String> problems;

        public ValidationException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    /** A well-formed request that breaks a business rule, e.g. insufficient funds (HTTP 422). */
    public static class BusinessRuleException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public BusinessRuleException(String m) {
            super(m);
        }
    }

    /** Unknown account, transfer or route (HTTP 404). */
    public static class NotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotFoundException(String m) {
            super(m);
        }
    }

    public record TransferRequest(String source, String destination, long amountCents, String memo) {}

    /**
     * Accepts a decimal string such as "125.50" and returns integer cents.
     * JSON numbers are rejected on purpose: binary floating point is unacceptable for money.
     */
    public static long parseAmount(Object raw) {
        if (!(raw instanceof String)) {
            throw new ValidationException(List.of("amount must be a decimal string, e.g. \"125.50\""));
        }
        String s = (String) raw;
        // Strict format first: BigDecimal alone would also accept "1e2" or "+5".
        if (TOO_MANY_DECIMALS.matcher(s).matches()) {
            throw new ValidationException(List.of("amount cannot have more than 2 decimal places"));
        }
        if (!AMOUNT_RE.matcher(s).matches()) {
            throw new ValidationException(List.of("amount is not a valid decimal number"));
        }
        BigDecimal cents = new BigDecimal(s).movePointRight(2);
        if (cents.signum() <= 0) {
            throw new ValidationException(List.of("amount must be greater than 0"));
        }
        if (cents.compareTo(BigDecimal.valueOf(MAX_TRANSFER_CENTS)) > 0) {
            throw new ValidationException(List.of("amount exceeds the per-transfer limit"));
        }
        return cents.longValueExact();
    }

    /** Validates the whole body and reports every problem at once, like the Python version. */
    public static TransferRequest validateTransfer(Object payload) {
        if (!(payload instanceof Map)) throw new ValidationException(List.of("body must be a JSON object"));
        Map<?, ?> p = (Map<?, ?>) payload;
        List<String> problems = new ArrayList<>();
        Object source = p.get("source");
        Object destination = p.get("destination");
        Object memo = p.containsKey("memo") ? p.get("memo") : "";
        if (!isAccount(source)) problems.add("source must look like ABC-123456");
        if (!isAccount(destination)) problems.add("destination must look like ABC-123456");
        if (source instanceof String && !((String) source).isEmpty() && source.equals(destination)) {
            problems.add("source and destination must differ");
        }
        if (!(memo instanceof String) || ((String) memo).length() > 140) {
            problems.add("memo must be a string of at most 140 characters");
        }
        long cents = 0;
        try {
            cents = parseAmount(p.get("amount"));
        } catch (ValidationException e) {
            problems.addAll(e.problems());
        }
        if (!problems.isEmpty()) throw new ValidationException(problems);
        return new TransferRequest((String) source, (String) destination, cents, (String) memo);
    }

    private static boolean isAccount(Object v) {
        return v instanceof String && ACCOUNT_RE.matcher((String) v).matches();
    }

    public static String formatCents(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
