# Transfer Ledger Service: Java port

A Java 17 port of my Python service [transfer-ledger-service](https://github.com/Carl090403/transfer-ledger-service). It keeps the **same HTTP contract and the same business rules**. This is the first item on that repo's "next steps" list. Like the original, it has zero runtime dependencies (JDK `HttpServer` only) and uses JUnit 4 for tests.

## Why a port?
Bank teams often work in Java, and an API is only as reliable as its contract. Porting the same service shows that the rules survive a change of language. The file `contract/validation_cases.json` holds language-neutral cases that **both** implementations must pass: 15 of 15 agree today.

## Run
```bash
mvn -B verify                                   # 28 JUnit tests; compiler warnings are errors
mvn -q compile && java -cp target/classes dev.carlosf.ledger.Api   # http://127.0.0.1:8080
curl -X POST localhost:8080/transfers -H "Content-Type: application/json" \
     -H "Idempotency-Key: demo-00000001" \
     -d '{"source":"CHQ-000001","destination":"SAV-000002","amount":"25.00"}'
curl localhost:8080/accounts/SAV-000002/balance     #for balance check
```
To check that the Python version agrees with the contract, run this from the root of the Python repo:
```bash
python path/to/contract/check_python.py path/to/contract/validation_cases.json
```

## API (identical to the Python service)
| Method | Path | Responses |
|---|---|---|
| POST | `/transfers` (`Idempotency-Key` header, 8 to 64 characters) | 201 created · 200 replay · 400 validation (all problems at once) · 404 unknown account · 422 insufficient funds or key reused with another body |
| GET | `/transfers/{id}` | 200 / 404 |
| GET | `/accounts/{id}/balance` | 200 / 404 |
| GET | `/health` | 200 |
| other methods | any path | 405 (JSON) |

## Design
- **Layers**: `Domain` (rules, no I/O), then `Ledger` (state and atomicity), then `Api` (HTTP). The business logic is tested without a network.
- **Money**: amounts must be decimal **strings** and are converted to integer cents with `BigDecimal`. JSON numbers, `NaN`, `1e2` and more than 2 decimals are rejected. There is a limit of 1,000,000.00 per transfer.
- **Idempotency**: the key is stored with the SHA-256 hash of a canonical form of the request. Replaying the same body returns the original transfer. Reusing the key with a different body returns 422. A rejected request is not stored, so it can be retried.
- **Concurrency**: one `ReentrantLock` covers the whole check-and-move. The test with 10 parallel transfers of 30.00 against 100.00 always gives exactly 3 successes and conserves the total money.
- **Observability and security basics**: `X-Request-Id` is echoed or generated, and each request writes one JSON log line with its latency. The body is read with a bound (8 KB), the content type is checked, `Cache-Control: no-store` is set, no stack traces are returned, and the audit trail contains no names or memos.
- **Testability**: the clock is injected, so timestamps are deterministic in tests.

## Honest limits
- Storage is in memory (the Python version uses SQLite), so a restart loses the data.
- The global lock is correct but limits throughput.
- The JSON parser is hand-written to avoid dependencies. In production I would use Jackson.
- There is no authentication.

## Next steps
1. Spring Boot 3 + JUnit 5 + MockMvc version, keeping the same contract file.
2. PostgreSQL with `SELECT ... FOR UPDATE` or row-level locking, plus Flyway migrations.
3. Dockerfile and docker-compose; OpenAPI specification.
4. Golang version of the domain rules, checked against the same contract.
