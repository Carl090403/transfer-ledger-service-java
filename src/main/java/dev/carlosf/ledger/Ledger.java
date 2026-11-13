package dev.carlosf.ledger;

import dev.carlosf.ledger.Domain.BusinessRuleException;
import dev.carlosf.ledger.Domain.NotFoundException;
import dev.carlosf.ledger.Domain.TransferRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory ledger: every transfer is atomic, idempotent and audited.
 * One lock guards the whole check-and-move, so a balance can never go negative under concurrency.
 */
public class Ledger {

    public record Transfer(String id, String source, String destination, long amountCents, String memo,
                           String createdAt) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("source", source);
            m.put("destination", destination);
            m.put("amount_cents", amountCents);
            m.put("memo", memo);
            m.put("created_at", createdAt);
            return m;
        }
    }

    public record Result(Transfer transfer, boolean created) {}

    public record AuditEvent(String at, String event, Map<String, Object> detail) {}

    private record Account(String id, String owner, long balanceCents) {}

    private record Stored(String requestHash, Transfer transfer) {}

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Account> accounts = new HashMap<>();
    private final Map<String, Stored> byKey = new HashMap<>();
    private final Map<String, Transfer> byId = new HashMap<>();
    private final List<AuditEvent> audit = new ArrayList<>();
    private final Clock clock;

    public Ledger() {
        this(Clock.systemUTC());
    }

    /** The clock is injectable so time-dependent output is deterministic in tests. */
    public Ledger(Clock clock) {
        this.clock = clock;
    }

    public void openAccount(String id, String owner, long balanceCents) {
        if (!Domain.ACCOUNT_RE.matcher(id).matches()) throw new IllegalArgumentException("bad account id");
        if (balanceCents < 0) throw new IllegalArgumentException("opening balance must be >= 0");
        lock.lock();
        try {
            if (accounts.containsKey(id)) throw new BusinessRuleException("account " + id + " already exists");
            accounts.put(id, new Account(id, owner, balanceCents));
            audit("account_opened", Map.of("account", id));
        } finally {
            lock.unlock();
        }
    }

    public long balance(String accountId) {
        lock.lock();
        try {
            Account a = accounts.get(accountId);
            if (a == null) throw new NotFoundException("account " + accountId + " not found");
            return a.balanceCents();
        } finally {
            lock.unlock();
        }
    }

    /** Replaying the same key with the same body returns the original transfer (created = false). */
    public Result transfer(TransferRequest req, String idempotencyKey) {
        String hash = requestHash(req);
        lock.lock();
        try {
            Stored existing = byKey.get(idempotencyKey);
            if (existing != null) {
                if (!existing.requestHash().equals(hash)) {
                    throw new BusinessRuleException("idempotency key was already used with a different request");
                }
                return new Result(existing.transfer(), false);
            }
            try {
                for (String acct : List.of(req.source(), req.destination())) {
                    if (!accounts.containsKey(acct)) throw new NotFoundException("account " + acct + " not found");
                }
                Account src = accounts.get(req.source());
                if (src.balanceCents() < req.amountCents()) throw new BusinessRuleException("insufficient funds");
                Account dst = accounts.get(req.destination());
                // All checks passed: the two balance updates below cannot fail, so the move is atomic.
                accounts.put(src.id(), new Account(src.id(), src.owner(), src.balanceCents() - req.amountCents()));
                accounts.put(dst.id(), new Account(dst.id(), dst.owner(), dst.balanceCents() + req.amountCents()));
                Transfer t = new Transfer(UUID.randomUUID().toString(), req.source(), req.destination(),
                        req.amountCents(), req.memo(), Instant.now(clock).toString());
                byKey.put(idempotencyKey, new Stored(hash, t));
                byId.put(t.id(), t);
                audit("transfer_created", Map.of("id", t.id(), "amount_cents", t.amountCents()));
                return new Result(t, true);
            } catch (RuntimeException e) {
                audit("transfer_rejected", Map.of("source", req.source(), "amount_cents", req.amountCents()));
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    public Transfer getTransfer(String id) {
        lock.lock();
        try {
            Transfer t = byId.get(id);
            if (t == null) throw new NotFoundException("transfer not found");
            return t;
        } finally {
            lock.unlock();
        }
    }

    public long totalMoney() {
        lock.lock();
        try {
            return accounts.values().stream().mapToLong(Account::balanceCents).sum();
        } finally {
            lock.unlock();
        }
    }

    public List<AuditEvent> auditEvents() {
        lock.lock();
        try {
            return List.copyOf(audit);
        } finally {
            lock.unlock();
        }
    }

    private void audit(String event, Map<String, Object> detail) {
        // Audit entries never contain owner names or memos (data minimisation).
        audit.add(new AuditEvent(Instant.now(clock).toString(), event, detail));
    }

    static String requestHash(TransferRequest r) {
        String canonical = Json.write(List.of(r.source(), r.destination(), r.amountCents(), r.memo()));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
