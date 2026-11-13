package dev.carlosf.ledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.carlosf.ledger.Domain.BusinessRuleException;
import dev.carlosf.ledger.Domain.NotFoundException;
import dev.carlosf.ledger.Domain.TransferRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Before;
import org.junit.Test;

public class LedgerTest {
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-12T15:00:00Z"), ZoneOffset.UTC);
    private Ledger ledger;

    @Before
    public void setUp() {
        ledger = new Ledger(FIXED);
        ledger.openAccount("CHQ-000001", "Alice", 100_00);
        ledger.openAccount("SAV-000002", "Bob", 0);
    }

    private static TransferRequest req(long cents) {
        return new TransferRequest("CHQ-000001", "SAV-000002", cents, "");
    }

    @Test
    public void transferMovesMoneyAndIsTimestampedWithInjectedClock() {
        Ledger.Result r = ledger.transfer(req(25_00), "key-00000001");
        assertTrue(r.created());
        assertEquals(75_00, ledger.balance("CHQ-000001"));
        assertEquals(25_00, ledger.balance("SAV-000002"));
        assertEquals("2026-11-12T15:00:00Z", r.transfer().createdAt());
        assertEquals(r.transfer(), ledger.getTransfer(r.transfer().id()));
    }

    @Test
    public void replayWithSameKeyAndBodyReturnsOriginalWithoutMovingMoneyTwice() {
        Ledger.Result first = ledger.transfer(req(25_00), "key-00000001");
        Ledger.Result replay = ledger.transfer(req(25_00), "key-00000001");
        assertFalse(replay.created());
        assertEquals(first.transfer(), replay.transfer());
        assertEquals(75_00, ledger.balance("CHQ-000001"));
    }

    @Test
    public void sameKeyWithDifferentBodyIsRefused() {
        ledger.transfer(req(25_00), "key-00000001");
        try {
            ledger.transfer(req(30_00), "key-00000001");
            fail();
        } catch (BusinessRuleException e) {
            assertEquals("idempotency key was already used with a different request", e.getMessage());
        }
        assertEquals(75_00, ledger.balance("CHQ-000001"));
    }

    @Test
    public void insufficientFundsLeavesBalancesUntouchedAndIsAudited() {
        try {
            ledger.transfer(req(100_01), "key-00000002");
            fail();
        } catch (BusinessRuleException e) {
            assertEquals("insufficient funds", e.getMessage());
        }
        assertEquals(100_00, ledger.balance("CHQ-000001"));
        assertEquals(0, ledger.balance("SAV-000002"));
        List<Ledger.AuditEvent> events = ledger.auditEvents();
        assertEquals("transfer_rejected", events.get(events.size() - 1).event());
    }

    @Test
    public void rejectedKeyCanBeRetriedAfterFundsArrive() {
        try {
            ledger.transfer(new TransferRequest("SAV-000002", "CHQ-000001", 10_00, ""), "key-00000003");
            fail();
        } catch (BusinessRuleException expected) {
            // a rejected request is not stored, so the client may retry it with the same key
        }
        ledger.transfer(req(10_00), "key-00000004");
        assertTrue(ledger.transfer(new TransferRequest("SAV-000002", "CHQ-000001", 10_00, ""), "key-00000003").created());
    }

    @Test(expected = NotFoundException.class)
    public void unknownAccount() {
        ledger.transfer(new TransferRequest("CHQ-000001", "ZZZ-999999", 1, ""), "key-00000005");
    }

    @Test(expected = BusinessRuleException.class)
    public void duplicateAccountIsRefused() {
        ledger.openAccount("CHQ-000001", "Mallory", 0);
    }

    @Test
    public void auditNeverContainsOwnerNamesOrMemos() {
        ledger.transfer(new TransferRequest("CHQ-000001", "SAV-000002", 1_00, "secret memo"), "key-00000006");
        String all = ledger.auditEvents().toString();
        assertFalse(all.contains("Alice"));
        assertFalse(all.contains("secret memo"));
    }

    /** Mirrors the Python test: 10 parallel transfers of 30.00 against 100.00, exactly 3 succeed. */
    @Test
    public void parallelTransfersNeverOverdrawAndConserveMoney() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int n = 0; n < 10; n++) {
            final String key = String.format("parallel-%04d", n);
            Callable<Boolean> task = () -> {
                go.await();
                try {
                    return ledger.transfer(req(30_00), key).created();
                } catch (BusinessRuleException e) {
                    return false;
                }
            };
            results.add(pool.submit(task));
        }
        go.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) if (f.get()) ok++;
        pool.shutdown();
        assertEquals(3, ok);
        assertEquals(10_00, ledger.balance("CHQ-000001"));
        assertEquals(100_00, ledger.totalMoney());
    }
}
