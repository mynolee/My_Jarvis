package dev.myjarvis.core;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL ledger and snapshot tests with synthetic counters; no model or provider is invoked. */
class UsageServiceTest extends ReminderTestSupport {
    UsageService usage() { return context.getBean(UsageService.class); }
    InferenceContextService contexts() { return context.getBean(InferenceContextService.class); }
    Instant now() { return AuthService.instant(db.queryForObject("SELECT clock_timestamp()", Object.class)); }
    JsonNode summary() { return body(usage().get(session)); }
    LocalInferenceService.Dispatch reservation(String status, boolean started, Instant created) { return reservation(status, started, created, 1); }
    LocalInferenceService.Dispatch reservation(String status, boolean started, Instant created, int accountingVersion) {
        UUID run = UUID.randomUUID(), conversation = UUID.randomUUID(), reservation = UUID.randomUUID(), requestId = UUID.randomUUID();
        db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'private usage conversation sentinel')", conversation, owner);
        db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,?)", run, owner, conversation, status.equals("STARTED") ? "RUNNING" : status);
        JsonNode request = body(Map.of("request_id", requestId, "run_id", run, "model_id", "local-mlx-v1", "model_manifest_hash", "a".repeat(64),
                "user_input", "private usage prompt sentinel", "budget", Map.of("reservation_id", reservation, "max_compute_ms", 60000,
                        "max_input_tokens", 3584, "max_output_tokens", 512, "max_model_calls", 1)));
        db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch,created_at,started_at,accounting_version) VALUES (?,?,?,?,?,?,?,60000,512,?,?,?,?)",
                reservation, owner, run, requestId, contexts().requestDigest(owner, Json.canonical(request)), "a".repeat(64), status, session.epoch(), Timestamp.from(created), started ? Timestamp.from(created) : null, accountingVersion);
        return new LocalInferenceService.Dispatch(owner, run, reservation, request);
    }
    LocalInferenceService.Dispatch reservation(String status, boolean started) { return reservation(status, started, now().minusSeconds(1)); }
    ObjectNode answer(LocalInferenceService.Dispatch dispatch) { return LocalInferenceContractTest.response(dispatch.body()); }
    JsonNode error(LocalInferenceService.Dispatch dispatch) {
        ObjectNode result = (ObjectNode)body(Map.of("kind", "ERROR", "error_code", "GENERATION_TIMEOUT", "retryable", false, "usage", answer(dispatch).get("usage")));
        for (String field : List.of("input_tokens", "output_tokens", "compute_ms", "peak_memory_bytes")) result.withObject("usage").put(field, 0);
        return result;
    }
    void zone(String zone) {
        db.update("UPDATE owners SET profile_ref=?,profile_updated_at=clock_timestamp() WHERE id=?", vault.seal(owner, owner, "owner-profile",
                Json.canonical(Map.of("display_name", "Synthetic", "locale", "en-US", "timezone", zone))), owner);
    }
    UsageService service(JdbcTemplate template) { return new UsageService(template, vault, contexts(), context.getBean(PlatformTransactionManager.class)); }
    UsageService at(Instant checked) {
        return service(new JdbcTemplate(Objects.requireNonNull(db.getDataSource())) {
            @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
                var rows = super.queryForList(sql, args);
                if (sql.startsWith("WITH checked AS MATERIALIZED") && !rows.isEmpty()) rows.getFirst().put("checked_at", Timestamp.from(checked));
                return rows;
            }
        });
    }
    Map<String,String> databaseContents() {
        Map<String,String> result = new TreeMap<>();
        for (String table : db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new AssertionError("Unexpected table identifier");
            result.put(table, db.queryForObject("SELECT COALESCE(jsonb_agg(value ORDER BY value::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) AS value FROM " + table + " t) rows", String.class));
        }
        return result;
    }

    @Test void emptySummaryHasClosedShapeUnknownCostAndNoObservedPeak() {
        Instant before = now(); JsonNode data = summary(); Instant after = now();
        assertEquals(Set.of("checked_at", "timezone", "attribution", "quota_timezone", "day", "month"), Set.copyOf(data.propertyNames()));
        assertEquals("UTC", data.path("timezone").asText()); assertEquals("UTC", data.path("quota_timezone").asText());
        assertEquals("RESERVATION_CREATED_AT", data.path("attribution").asText());
        Instant checked = Instant.parse(data.path("checked_at").asText()); assertFalse(checked.isBefore(before)); assertFalse(checked.isAfter(after));
        for (String field : List.of("day", "month")) {
            JsonNode period = data.path(field);
            assertEquals(Set.of("local_key", "start_at", "end_at", "request_count", "counts", "measured", "conservative_reserved"), Set.copyOf(period.propertyNames()));
            assertEquals(0, period.path("request_count").asLong()); assertEquals(6, period.path("counts").size());
            assertTrue(period.path("measured").path("peak_memory_bytes").isNull()); assertTrue(period.path("measured").path("external_cost_usd_micro").isNull());
            assertEquals(0, period.path("measured").path("compute_ms").asLong()); assertEquals(0, period.path("conservative_reserved").path("max_compute_ms").asLong());
        }
    }

    @Test void sixDisjointClassesNeverLabelActiveOrLegacyWorkAsZeroMeasured() {
        var measured = reservation("SUCCEEDED", true); usage().record(measured, answer(measured));
        reservation("QUEUED", false); reservation("STARTED", true); reservation("CANCELLED", false); reservation("UNKNOWN", true);
        reservation("SUCCEEDED", true, now().minusSeconds(1), 0);
        JsonNode period = summary().path("day"); assertEquals(6, period.path("request_count").asLong());
        for (JsonNode count : period.path("counts")) assertEquals(1, count.asLong());
        assertEquals(5, period.path("conservative_reserved").path("request_count").asLong());
        assertEquals(300000, period.path("conservative_reserved").path("max_compute_ms").asLong());
        assertEquals(2560, period.path("conservative_reserved").path("max_output_tokens").asLong());
        assertEquals(4, period.path("measured").path("input_tokens").asLong()); assertEquals(1, period.path("measured").path("compute_ms").asLong());
    }

    @Test void erroneousDispatchMarkersFailClosedToUncertain() {
        reservation("QUEUED", true); reservation("STARTED", false); reservation("CANCELLED", true);
        JsonNode counts = summary().path("day").path("counts"); assertEquals(3, counts.path("uncertain").asLong());
        assertEquals(0, counts.path("pending").asLong()); assertEquals(0, counts.path("in_progress").asLong()); assertEquals(0, counts.path("unstarted").asLong());
    }

    @Test void duplicateMeasurementIsHarmlessAndConflictingMeasurementCannotOverwrite() {
        var dispatch = reservation("STARTED", true); var response = answer(dispatch);
        usage().record(dispatch, response); String original = Json.canonical(db.queryForList("SELECT * FROM inference_usage_receipts"));
        usage().record(dispatch, response); assertEquals(original, Json.canonical(db.queryForList("SELECT * FROM inference_usage_receipts")));
        response.withObject("usage").put("compute_ms", 2); code("USAGE_MEASUREMENT_CONFLICT", () -> usage().record(dispatch, response));
        assertEquals(original, Json.canonical(db.queryForList("SELECT * FROM inference_usage_receipts"))); assertEquals(1, count("inference_usage_receipts"));
    }

    @Test void concurrentIdenticalAndConflictingReceiptsHaveOneImmutableWinner() throws Exception {
        var dispatch = reservation("STARTED", true);
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) { final int value = i % 2; calls.add(() -> {
                var response = answer(dispatch); response.withObject("usage").put("compute_ms", value);
                try { usage().record(dispatch, response); return true; } catch (ApiException error) { assertEquals("USAGE_MEASUREMENT_CONFLICT", error.code); return false; }
            }); }
            int accepted = 0; for (var future : pool.invokeAll(calls, 20, TimeUnit.SECONDS)) { assertFalse(future.isCancelled()); if (future.get()) accepted++; }
            assertEquals(2, accepted);
        }
        assertEquals(1, count("inference_usage_receipts"));
    }

    @Test void ledgerRepeatsFullResponseAndSafeIntegerValidation() {
        var dispatch = reservation("STARTED", true);
        for (String field : List.of("input_tokens", "output_tokens", "compute_ms", "peak_memory_bytes")) {
            var negative = answer(dispatch); negative.withObject("usage").put(field, -1); assertThrows(ApiException.class, () -> usage().record(dispatch, negative));
            var fractional = answer(dispatch); fractional.withObject("usage").put(field, 1.5); assertThrows(ApiException.class, () -> usage().record(dispatch, fractional));
        }
        for (var field : Map.of("input_tokens", 3585L, "output_tokens", 513L, "compute_ms", 60001L, "peak_memory_bytes", UsageService.MAX + 1).entrySet()) {
            var response = answer(dispatch); response.withObject("usage").put(field.getKey(), field.getValue()); assertThrows(ApiException.class, () -> usage().record(dispatch, response));
        }
        var extra = answer(dispatch); extra.put("approved", true); assertThrows(ApiException.class, () -> usage().record(dispatch, extra));
        var missing = answer(dispatch); missing.remove("reply_text"); assertThrows(ApiException.class, () -> usage().record(dispatch, missing));
        assertEquals(0, count("inference_usage_receipts"));
    }

    @Test void durableBoundaryBindsOwnerRunRequestReservationManifestModelAndExactRequest() {
        var dispatch = reservation("STARTED", true); var response = answer(dispatch);
        for (String field : List.of("inference_request_id", "reservation_id", "model_manifest_hash")) {
            var forged = answer(dispatch); forged.withObject("usage").put(field, field.equals("model_manifest_hash") ? "b".repeat(64) : UUID.randomUUID().toString());
            assertThrows(ApiException.class, () -> usage().record(dispatch, forged));
        }
        assertThrows(ApiException.class, () -> usage().record(new LocalInferenceService.Dispatch(UUID.randomUUID(), dispatch.run(), dispatch.reservation(), dispatch.body()), response));
        assertThrows(ApiException.class, () -> usage().record(new LocalInferenceService.Dispatch(owner, UUID.randomUUID(), dispatch.reservation(), dispatch.body()), response));
        assertThrows(ApiException.class, () -> usage().record(new LocalInferenceService.Dispatch(owner, dispatch.run(), UUID.randomUUID(), dispatch.body()), response));
        for (String field : List.of("model_id", "user_input")) {
            ObjectNode request = (ObjectNode)dispatch.body().deepCopy(); request.put(field, "tampered");
            assertThrows(ApiException.class, () -> usage().record(new LocalInferenceService.Dispatch(owner, dispatch.run(), dispatch.reservation(), request), response));
        }
        ObjectNode request = (ObjectNode)dispatch.body().deepCopy(); request.withObject("budget").put("max_output_tokens", 511);
        assertThrows(ApiException.class, () -> usage().record(new LocalInferenceService.Dispatch(owner, dispatch.run(), dispatch.reservation(), request), response));
        assertEquals(0, count("inference_usage_receipts"));
    }

    @Test void reservationThatNeverStartedCannotAcquireMeasurement() {
        var pending = reservation("QUEUED", false); var cancelled = reservation("CANCELLED", false);
        assertThrows(ApiException.class, () -> usage().record(pending, answer(pending))); assertThrows(ApiException.class, () -> usage().record(cancelled, answer(cancelled)));
        assertEquals(0, count("inference_usage_receipts"));
    }

    @Test void errorZeroPlaceholdersAreUncertainEvenBeforeRunSettlement() {
        var dispatch = reservation("STARTED", true); usage().record(dispatch, error(dispatch));
        var period = summary().path("day"); assertEquals(1, period.path("counts").path("uncertain").asLong()); assertEquals(0, period.path("counts").path("in_progress").asLong());
        assertEquals(0, period.path("measured").path("request_count").asLong()); assertTrue(period.path("measured").path("peak_memory_bytes").isNull());
        assertEquals(60000, period.path("conservative_reserved").path("max_compute_ms").asLong());
    }

    @Test void lateActualAfterErrorUsesSeparateImmutableSlotAndCountsRequestOnce() {
        var dispatch = reservation("UNKNOWN", true); usage().record(dispatch, error(dispatch)); usage().record(dispatch, answer(dispatch));
        assertEquals(2, count("inference_usage_receipts")); var period = summary().path("day");
        assertEquals(1, period.path("request_count").asLong()); assertEquals(1, period.path("counts").path("measured").asLong()); assertEquals(0, period.path("counts").path("uncertain").asLong());
        assertEquals("UNKNOWN", db.queryForObject("SELECT status FROM runs WHERE id=?", String.class, dispatch.run()));
        assertEquals("UNKNOWN", db.queryForObject("SELECT status FROM inference_reservations WHERE id=?", String.class, dispatch.reservation()));
        assertEquals(0, count("authorizations")); assertEquals(0, count("messages"));
    }

    @Test void measurementCommitSurvivesOuterRollbackAndServiceRestart() {
        var dispatch = reservation("UNKNOWN", true);
        assertThrows(IllegalStateException.class, () -> flow.tx.executeWithoutResult(tx -> {
            db.queryForObject("SELECT 1", Integer.class); usage().record(dispatch, answer(dispatch)); throw new IllegalStateException("Synthetic outer rollback");
        }));
        assertEquals(1, count("inference_usage_receipts")); context.close(); open();
        assertEquals(1, summary().path("day").path("counts").path("measured").asLong());
        assertEquals("UNKNOWN", db.queryForObject("SELECT status FROM runs WHERE id=?", String.class, dispatch.run()));
        usage().record(dispatch, answer(dispatch)); assertEquals(1, count("inference_usage_receipts"));
    }

    @Test void originalCreationAttributionSurvivesLateRecordingAndCannotBeMoved() {
        Instant first = now().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        var previous = reservation("UNKNOWN", true, first.minusSeconds(1));
        assertThrows(Exception.class, () -> db.update("UPDATE inference_reservations SET max_compute_ms=59999 WHERE id=?", previous.reservation()));
        assertThrows(Exception.class, () -> db.update("UPDATE inference_reservations SET created_at=clock_timestamp() WHERE id=?", previous.reservation()));
        usage().record(previous, answer(previous));
        assertEquals(0, summary().path("month").path("request_count").asLong());
        assertThrows(Exception.class, () -> db.update("UPDATE inference_reservations SET created_at=clock_timestamp() WHERE id=?", previous.reservation()));
        assertEquals(first.minusSeconds(1), AuthService.instant(db.queryForObject("SELECT reserved_at FROM inference_usage_receipts", Object.class)));
    }

    @Test void measuredPeakIsMaximumRatherThanSumAndReportedZeroIsPreserved() {
        var first = reservation("SUCCEEDED", true); var second = reservation("SUCCEEDED", true);
        var a = answer(first); var b = answer(second); a.withObject("usage").put("peak_memory_bytes", 0); b.withObject("usage").put("peak_memory_bytes", 2048);
        usage().record(first, a); assertEquals(0, summary().path("day").path("measured").path("peak_memory_bytes").asLong());
        usage().record(second, b); JsonNode measured = summary().path("day").path("measured");
        assertEquals(2048, measured.path("peak_memory_bytes").asLong()); assertEquals(8, measured.path("input_tokens").asLong()); assertEquals(6, measured.path("output_tokens").asLong());
    }

    @Test void profileTimezoneChangesDisplayWindowsWithoutChangingQuotaOrRows() {
        zone("Asia/Seoul"); reservation("UNKNOWN", true); var before = databaseContents();
        JsonNode data = summary(); assertEquals("Asia/Seoul", data.path("timezone").asText()); assertEquals("UTC", data.path("quota_timezone").asText());
        var day = UsageService.window(Instant.parse(data.path("checked_at").asText()), "Asia/Seoul", false);
        assertEquals(day.start().toString(), data.path("day").path("start_at").asText()); assertEquals(before, databaseContents());
    }

    @Test void localDayMonthBoundariesCoverDstLeapMonthAndHalfOpenMidnight() {
        for (var vector : List.of(
                List.of("2026-03-08T07:30:00Z", "America/New_York", "2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z"),
                List.of("2026-11-01T07:30:00Z", "America/New_York", "2026-11-01T04:00:00Z", "2026-11-02T05:00:00Z"),
                List.of("2026-10-09T15:00:00Z", "Asia/Seoul", "2026-10-09T15:00:00Z", "2026-10-10T15:00:00Z"))) {
            var day = UsageService.window(Instant.parse(vector.get(0)), vector.get(1), false);
            assertEquals(Instant.parse(vector.get(2)), day.start()); assertEquals(Instant.parse(vector.get(3)), day.end());
        }
        var month = UsageService.window(Instant.parse("2024-02-29T20:00:00Z"), "UTC", true);
        assertEquals("2024-02", month.key()); assertEquals(Instant.parse("2024-02-01T00:00:00Z"), month.start()); assertEquals(Instant.parse("2024-03-01T00:00:00Z"), month.end());
        assertThrows(IllegalStateException.class, () -> UsageService.window(Instant.parse("9999-12-31T12:00:00Z"), "UTC", true));
    }

    @Test void dayAndMonthUseHalfOpenOriginalRequestTimeAndExcludeFutureRows() {
        Instant checked = now(); var day = UsageService.window(checked, "UTC", false);
        reservation("QUEUED", false, day.start()); reservation("QUEUED", false, day.start().minusNanos(1000));
        reservation("QUEUED", false, day.end()); reservation("QUEUED", false, checked.plusSeconds(1));
        assertEquals(1, body(at(checked).get(session)).path("day").path("request_count").asLong());
    }

    @Test void aReceiptAfterCheckedTimeIsNotPresentedAsAlreadyObserved() {
        Instant checked = now(); var dispatch = reservation("STARTED", true, checked.minusSeconds(5));
        usage().record(dispatch, answer(dispatch)); var data = body(at(checked).get(session));
        assertEquals(0, data.path("day").path("counts").path("measured").asLong()); assertEquals(1, data.path("day").path("counts").path("in_progress").asLong());
    }

    @Test void snapshotCannotMixConcurrentReceiptProfileAndSessionChanges() {
        var dispatch = reservation("UNKNOWN", true);
        JdbcTemplate intercepted = new JdbcTemplate(Objects.requireNonNull(db.getDataSource())) {
            boolean changed;
            @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
                var rows = super.queryForList(sql, args);
                if (!changed && sql.startsWith("WITH checked AS MATERIALIZED")) {
                    changed = true; assertEquals("on", super.queryForObject("SHOW transaction_read_only", String.class));
                    assertEquals("repeatable read", super.queryForObject("SHOW transaction_isolation", String.class));
                    try (var pool = Executors.newSingleThreadExecutor()) {
                        pool.submit(() -> { usage().record(dispatch, answer(dispatch)); zone("Asia/Seoul"); auth.logout(session); }).get(10, TimeUnit.SECONDS);
                    } catch (Exception error) { throw new AssertionError(error); }
                }
                return rows;
            }
        };
        JsonNode data = body(service(intercepted).get(session)); assertEquals("UTC", data.path("timezone").asText());
        for (String field : List.of("day", "month")) { assertEquals(1, data.path(field).path("counts").path("uncertain").asLong()); assertEquals(0, data.path(field).path("counts").path("measured").asLong()); }
        code("SESSION_REVOKED", () -> usage().get(session));
    }

    @Test void ownerSessionExpiryEpochAndLogoutAreRevalidatedWithinReadSnapshot() {
        code("AUTHENTICATION_REQUIRED", () -> usage().get(null));
        code("SESSION_REVOKED", () -> usage().get(new AuthService.Session(session.hash(), UUID.randomUUID(), session.reauth(), session.epoch())));
        code("SESSION_REVOKED", () -> usage().get(new AuthService.Session("foreign-session", owner, session.reauth(), session.epoch())));
        db.update("UPDATE sessions SET last_seen_at=now()-interval '3 hours'"); code("SESSION_REVOKED", () -> usage().get(session));
        db.update("UPDATE sessions SET last_seen_at=now(),created_at=now()-interval '13 hours'"); code("SESSION_REVOKED", () -> usage().get(session));
        db.update("UPDATE sessions SET created_at=now(),revoked_at=now()"); code("SESSION_REVOKED", () -> usage().get(session));
        db.update("UPDATE sessions SET revoked_at=NULL"); db.update("UPDATE owners SET security_epoch=security_epoch+1"); code("SESSION_REVOKED", () -> usage().get(session));
    }

    @Test void readDoesNotMutateAnyTableOrExposePrivateIdentityAndContent() {
        var dispatch = reservation("UNKNOWN", true); usage().record(dispatch, answer(dispatch)); db.update("UPDATE owners SET execution_paused=true");
        var before = databaseContents(); String data = summary().toString(); summary(); assertEquals(before, databaseContents());
        for (String secret : List.of("private usage", owner.toString(), dispatch.run().toString(), dispatch.reservation().toString(), session.hash(), "a".repeat(64), "body_hash", "request_id")) assertFalse(data.contains(secret), secret);
        assertEquals(0, count("jobs")); assertEquals(0, count("authorizations"));
    }

    @Test void receiptRowsRejectUpdateAndDeleteAndForeignBindingAtDatabaseBoundary() {
        var dispatch = reservation("UNKNOWN", true); usage().record(dispatch, answer(dispatch));
        assertThrows(Exception.class, () -> db.update("UPDATE inference_usage_receipts SET compute_ms=0"));
        assertThrows(Exception.class, () -> db.update("DELETE FROM inference_usage_receipts"));
        assertThrows(Exception.class, () -> db.update("UPDATE inference_reservations SET request_id=? WHERE id=?", UUID.randomUUID(), dispatch.reservation()));
        for (String assignment : List.of("max_compute_ms=59999", "reserved_output_tokens=511", "body_hash='" + "b".repeat(64) + "'", "accounting_version=0", "started_at=NULL"))
            assertThrows(Exception.class, () -> db.update("UPDATE inference_reservations SET " + assignment + " WHERE id=?", dispatch.reservation()));
        var receipt = db.queryForMap("SELECT request_digest,budget_revision,max_compute_ms,reserved_output_tokens FROM inference_usage_receipts");
        assertEquals(contexts().requestDigest(owner, Json.canonical(dispatch.body())), receipt.get("request_digest"));
        assertEquals(1, ((Number)receipt.get("budget_revision")).intValue()); assertEquals(60000, ((Number)receipt.get("max_compute_ms")).longValue());
        assertEquals(512, ((Number)receipt.get("reserved_output_tokens")).longValue());
        assertEquals(1, count("inference_usage_receipts"));
    }

    @Test void corruptProfilesAndDatabaseFailureDoNotMasqueradeAsEmptyUsage() {
        for (String zone : List.of("Unknown/Zone", "SystemV/EST5", "GMT+04:00")) { zone(zone); assertThrows(IllegalStateException.class, () -> usage().get(session)); }
        db.update("UPDATE owners SET profile_ref=NULL,profile_updated_at=NULL");
        db.execute("ALTER TABLE inference_usage_receipts RENAME TO hidden_usage_receipts");
        try { assertThrows(org.springframework.dao.DataAccessException.class, () -> usage().get(session)); }
        finally { db.execute("ALTER TABLE hidden_usage_receipts RENAME TO inference_usage_receipts"); }
    }

    @Test void aggregateNumbersFailClosedOutsideSafeIntegerRange() {
        assertEquals(UsageService.MAX, UsageService.number(new BigDecimal("9007199254740991")));
        for (Object value : List.of(new BigDecimal("9007199254740992"), new BigDecimal("9223372036854775808"), -1, new BigDecimal("1.5"), "not-a-number"))
            code("USAGE_AGGREGATE_OVERFLOW", () -> UsageService.number(value));
    }
    @Test void migrationMarksHistoricalReservationsUnavailableAndNewAdmissionsVersionOne() {
        String schema = "usage_migration_fixture";
        db.execute("CREATE SCHEMA " + schema);
        try {
            org.flywaydb.core.Flyway.configure().dataSource(Objects.requireNonNull(db.getDataSource()))
                    .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                    .target(org.flywaydb.core.api.MigrationVersion.fromVersion("11")).load().migrate();
            UUID oldOwner = UUID.randomUUID(), conversation = UUID.randomUUID(), run = UUID.randomUUID(), oldReservation = UUID.randomUUID();
            db.update("INSERT INTO " + schema + ".owners(id,login_name,password_hash) VALUES (?,'migration-owner','unused')", oldOwner);
            db.update("INSERT INTO " + schema + ".conversations(id,owner_id,title) VALUES (?,?,'Synthetic migration')", conversation, oldOwner);
            db.update("INSERT INTO " + schema + ".runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'SUCCEEDED')", run, oldOwner, conversation);
            db.update("INSERT INTO " + schema + ".inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch) VALUES (?,?,?,?,?,?,'SUCCEEDED',60000,512,1)",
                    oldReservation, oldOwner, run, UUID.randomUUID(), "0".repeat(64), "a".repeat(64));
            org.flywaydb.core.Flyway.configure().dataSource(Objects.requireNonNull(db.getDataSource()))
                    .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                    .target(org.flywaydb.core.api.MigrationVersion.fromVersion("12")).load().migrate();
            assertEquals(0, db.queryForObject("SELECT accounting_version FROM " + schema + ".inference_reservations WHERE id=?", Integer.class, oldReservation));
            assertEquals(0L, db.queryForObject("SELECT count(*) FROM " + schema + ".inference_usage_receipts", Long.class));
            UUID newRun = UUID.randomUUID();
            db.update("INSERT INTO " + schema + ".runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'QUEUED')", newRun, oldOwner, conversation);
            db.update("INSERT INTO " + schema + ".inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch) VALUES (?,?,?,?,?,?,'QUEUED',60000,512,1)",
                    UUID.randomUUID(), oldOwner, newRun, UUID.randomUUID(), "0".repeat(64), "a".repeat(64));
            assertEquals(1, db.queryForObject("SELECT accounting_version FROM " + schema + ".inference_reservations WHERE run_id=?", Integer.class, newRun));
        } finally { db.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    @Test void foreignOwnerReservationsCannotEnterSummaryOrForgeReceiptBinding() {
        reservation("QUEUED", false); UUID original = owner, foreign = UUID.randomUUID();
        db.execute("ALTER TABLE owners DROP CONSTRAINT owners_singleton_key");
        try {
            db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'foreign-usage-owner','unused')", foreign);
            owner = foreign; var other = reservation("SUCCEEDED", true); usage().record(other, answer(other)); owner = original;
            JsonNode period = summary().path("day"); assertEquals(1, period.path("request_count").asLong());
            assertEquals(1, period.path("counts").path("pending").asLong()); assertEquals(0, period.path("counts").path("measured").asLong());
            code("SESSION_REVOKED", () -> usage().get(new AuthService.Session(session.hash(), foreign, session.reauth(), session.epoch())));
            assertThrows(Exception.class, () -> db.update("INSERT INTO inference_usage_receipts(owner_id,run_id,reservation_id,request_id,model_id,model_manifest_hash,reserved_at,request_digest,budget_revision,max_compute_ms,reserved_output_tokens,receipt_kind,response_kind,error_code,input_tokens,output_tokens,compute_ms,peak_memory_bytes) SELECT ?,run_id,reservation_id,request_id,model_id,model_manifest_hash,reserved_at,request_digest,budget_revision,max_compute_ms,reserved_output_tokens,'ERROR','ERROR','CANCELLED',0,0,0,0 FROM inference_usage_receipts WHERE reservation_id=?", original, other.reservation()));
        } finally {
            owner = original; db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE");
            db.execute("ALTER TABLE owners ADD CONSTRAINT owners_singleton_key UNIQUE(singleton)");
        }
    }

}
