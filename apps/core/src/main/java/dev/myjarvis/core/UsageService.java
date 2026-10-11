package dev.myjarvis.core;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Immutable facts and a bounded read model. Usage observations never issue execution authority. */
@Service
final class UsageService {
    static final long MAX = 9007199254740991L;
    private final JdbcTemplate db;
    private final Vault vault;
    private final InferenceContextService contexts;
    private final TransactionTemplate recordTx, readTx;

    UsageService(JdbcTemplate db, Vault vault, InferenceContextService contexts, PlatformTransactionManager manager) {
        this.db = db; this.vault = vault; this.contexts = contexts;
        recordTx = new TransactionTemplate(manager);
        recordTx.setTimeout(15);
        recordTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readTx = new TransactionTemplate(manager);
        readTx.setReadOnly(true); readTx.setTimeout(15);
        readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A transport response may arrive after revocation. Record facts first, never restore its context. */
    void record(LocalInferenceService.Dispatch dispatch, JsonNode response) {
        // Revalidate the entire response and all numeric bounds at the durable boundary.
        JsonNode request = dispatch.body();
        Json.validate(response); LocalInferenceService.validate(response, request);
        if (!dispatch.run().equals(Json.uuid(request, "run_id"))
                || !dispatch.reservation().equals(Json.uuid(request.get("budget"), "reservation_id"))
                || !"local-mlx-v1".equals(Json.string(request, "model_id", 40))) throw ApiException.bad();
        UUID requestId = Json.uuid(request, "request_id");
        String manifest = Json.string(request, "model_manifest_hash", 64);
        if (!manifest.matches("[0-9a-f]{64}")) throw ApiException.bad();
        JsonNode budget = request.get("budget"), usage = response.get("usage");
        if (Json.integer(budget, "max_input_tokens") != 3584 || Json.integer(budget, "max_model_calls") != 1)
            throw ApiException.bad();
        String kind = response.get("kind").asText(), slot = kind.equals("ERROR") ? "ERROR" : "MEASURED";
        String error = kind.equals("ERROR") ? response.get("error_code").asText() : null;
        long input = Json.integer(usage, "input_tokens"), output = Json.integer(usage, "output_tokens"),
                compute = Json.integer(usage, "compute_ms"), peak = Json.integer(usage, "peak_memory_bytes");
        String digest = contexts.requestDigest(dispatch.owner(), Json.canonical(request));
        recordTx.executeWithoutResult(transaction -> {
            // Same owner-first ordering as cancellation; no current-session/freshness gate here.
            if (db.queryForList("SELECT id FROM owners WHERE id=? FOR UPDATE", dispatch.owner()).isEmpty()) throw ApiException.missing();
            var rows = db.queryForList("SELECT * FROM inference_reservations WHERE owner_id=? AND run_id=? AND id=? FOR UPDATE",
                    dispatch.owner(), dispatch.run(), dispatch.reservation());
            if (rows.isEmpty()) throw ApiException.missing();
            var reservation = rows.getFirst();
            if (!StudyLessonResult.purpose(request).equals(reservation.get("purpose")) || !requestId.equals(reservation.get("request_id")) || !"local-mlx-v1".equals(reservation.get("model_id"))
                    || !Json.equal(manifest, (String)reservation.get("model_manifest_hash"))
                    || !Json.equal(digest, (String)reservation.get("body_hash")) || reservation.get("started_at") == null
                    || ((Number)reservation.get("budget_revision")).longValue() != 1
                    || Json.integer(budget, "max_compute_ms") != ((Number)reservation.get("max_compute_ms")).longValue()
                    || Json.integer(budget, "max_output_tokens") != ((Number)reservation.get("reserved_output_tokens")).longValue()
                    || compute > ((Number)reservation.get("max_compute_ms")).longValue()
                    || output > ((Number)reservation.get("reserved_output_tokens")).longValue()) throw ApiException.bad();
            var prior = db.queryForList("SELECT response_kind,error_code,input_tokens,output_tokens,compute_ms,peak_memory_bytes FROM inference_usage_receipts WHERE reservation_id=? AND receipt_kind=?",
                    dispatch.reservation(), slot);
            if (!prior.isEmpty()) {
                var row = prior.getFirst();
                if (!kind.equals(row.get("response_kind")) || !Objects.equals(error, row.get("error_code"))
                        || input != ((Number)row.get("input_tokens")).longValue() || output != ((Number)row.get("output_tokens")).longValue()
                        || compute != ((Number)row.get("compute_ms")).longValue() || peak != ((Number)row.get("peak_memory_bytes")).longValue())
                    throw new ApiException(409, "USAGE_MEASUREMENT_CONFLICT");
                return;
            }
            db.update("INSERT INTO inference_usage_receipts(owner_id,run_id,reservation_id,request_id,model_id,model_manifest_hash,reserved_at,request_digest,budget_revision,max_compute_ms,reserved_output_tokens,receipt_kind,response_kind,error_code,input_tokens,output_tokens,compute_ms,peak_memory_bytes) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    dispatch.owner(), dispatch.run(), dispatch.reservation(), requestId, "local-mlx-v1", manifest,
                    reservation.get("created_at"), digest, 1, reservation.get("max_compute_ms"), reservation.get("reserved_output_tokens"),
                    slot, kind, error, input, output, compute, peak);
        });
    }

    Map<String,Object> get(AuthService.Session session) {
        AuthService.requireOwner(session);
        return readTx.execute(transaction -> {
            // Owner, live session, profile, both periods and both ledgers share one read-only MVCC snapshot.
            var owners = db.queryForList("WITH checked AS MATERIALIZED (SELECT clock_timestamp() AS at) SELECT o.id,o.profile_ref,checked.at AS checked_at FROM owners o JOIN sessions s ON s.owner_id=o.id CROSS JOIN checked WHERE o.id=? AND o.security_epoch=? AND s.token_hash=? AND s.security_epoch=o.security_epoch AND s.revoked_at IS NULL AND s.created_at>checked.at-interval '12 hours' AND s.last_seen_at>checked.at-interval '2 hours'",
                    session.owner(), session.epoch(), session.hash());
            if (owners.isEmpty()) throw new ApiException(401, "SESSION_REVOKED");
            var owner = owners.getFirst(); Instant checked = AuthService.instant(owner.get("checked_at"));
            String timezone = timezone(owner, session.owner());
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("checked_at", outputInstant(checked)); result.put("timezone", timezone);
            result.put("attribution", "RESERVATION_CREATED_AT"); result.put("quota_timezone", "UTC");
            result.put("day", period(session.owner(), window(checked, timezone, false), checked));
            result.put("month", period(session.owner(), window(checked, timezone, true), checked));
            return result;
        });
    }

    private Map<String,Object> period(UUID owner, Window window, Instant checked) {
        // Fixed current day/month ranges use the owner/time index. Neither unbounded records nor payloads leave the DB.
        var row = db.queryForMap("""
            WITH classified AS (
              SELECT r.*,u.input_tokens,u.output_tokens,u.compute_ms,u.peak_memory_bytes,
                CASE WHEN u.reservation_id IS NOT NULL THEN 'measured'
                  WHEN e.reservation_id IS NOT NULL THEN 'uncertain'
                  WHEN r.accounting_version=0 THEN 'legacy_unavailable'
                  WHEN r.status='CANCELLED' AND r.started_at IS NULL THEN 'unstarted'
                  WHEN r.status='QUEUED' AND r.started_at IS NULL THEN 'pending'
                  WHEN r.status='STARTED' AND r.started_at IS NOT NULL THEN 'in_progress'
                  ELSE 'uncertain' END AS classification
              FROM inference_reservations r
              LEFT JOIN inference_usage_receipts u ON u.owner_id=r.owner_id AND u.reservation_id=r.id AND u.receipt_kind='MEASURED' AND u.recorded_at<=?
              LEFT JOIN inference_usage_receipts e ON e.owner_id=r.owner_id AND e.reservation_id=r.id AND e.receipt_kind='ERROR' AND e.recorded_at<=?
              WHERE r.owner_id=? AND r.created_at>=? AND r.created_at<? AND r.created_at<=?
            )
            SELECT count(*) AS request_count,
              count(*) FILTER (WHERE classification='measured') AS measured,
              count(*) FILTER (WHERE classification='pending') AS pending,
              count(*) FILTER (WHERE classification='in_progress') AS in_progress,
              count(*) FILTER (WHERE classification='unstarted') AS unstarted,
              count(*) FILTER (WHERE classification='uncertain') AS uncertain,
              count(*) FILTER (WHERE classification='legacy_unavailable') AS legacy_unavailable,
              COALESCE(sum(input_tokens) FILTER (WHERE classification='measured'),0) AS input_tokens,
              COALESCE(sum(output_tokens) FILTER (WHERE classification='measured'),0) AS output_tokens,
              COALESCE(sum(compute_ms) FILTER (WHERE classification='measured'),0) AS compute_ms,
              COALESCE(max(peak_memory_bytes) FILTER (WHERE classification='measured'),0) AS peak_memory_bytes,
              count(*) FILTER (WHERE status<>'CANCELLED') AS reserved_count,
              COALESCE(sum(max_compute_ms) FILTER (WHERE status<>'CANCELLED'),0) AS max_compute_ms,
              COALESCE(sum(reserved_output_tokens) FILTER (WHERE status<>'CANCELLED'),0) AS max_output_tokens
            FROM classified
            """, Timestamp.from(checked), Timestamp.from(checked), owner, Timestamp.from(window.start()), Timestamp.from(window.end()), Timestamp.from(checked));
        Map<String,Object> counts = new LinkedHashMap<>();
        for (String field : List.of("measured", "pending", "in_progress", "unstarted", "uncertain", "legacy_unavailable"))
            counts.put(field, number(row.get(field)));
        Map<String,Object> measured = new LinkedHashMap<>(); measured.put("request_count", counts.get("measured"));
        for (String field : List.of("input_tokens", "output_tokens", "compute_ms", "peak_memory_bytes")) measured.put(field, number(row.get(field)));
        if (((Long)counts.get("measured")) == 0L) measured.put("peak_memory_bytes", null);
        measured.put("external_cost_usd_micro", null);
        return Map.of("local_key", window.key(), "start_at", outputInstant(window.start()), "end_at", outputInstant(window.end()),
                "request_count", number(row.get("request_count")), "counts", counts, "measured", measured,
                "conservative_reserved", Map.of("request_count", number(row.get("reserved_count")),
                        "max_compute_ms", number(row.get("max_compute_ms")), "max_output_tokens", number(row.get("max_output_tokens"))));
    }

    static long number(Object value) {
        try {
            long result = new BigDecimal(value.toString()).longValueExact();
            if (result < 0 || result > MAX) throw new ArithmeticException();
            return result;
        } catch (Exception invalid) { throw new ApiException(500, "USAGE_AGGREGATE_OVERFLOW"); }
    }

    private String timezone(Map<String,Object> owner, UUID id) {
        if (owner.get("profile_ref") == null) return "UTC";
        try {
            JsonNode profile = Json.parse(vault.open(id, id, "owner-profile", (String)owner.get("profile_ref")).getBytes(StandardCharsets.UTF_8));
            Json.fields(profile, "display_name", "locale", "timezone");
            if (profile.size() != 3 || !OwnerProfileService.LOCALES.contains(Json.string(profile, "locale", 5))) throw new IllegalStateException("Invalid profile record");
            if (!profile.get("display_name").isNull()) {
                String name = Json.string(profile, "display_name", 80);
                if (name.codePoints().allMatch(OwnerProfileService::blankCodePoint) || name.codePoints().anyMatch(Character::isISOControl)
                        || name.getBytes(StandardCharsets.UTF_8).length > 320) throw new IllegalStateException("Invalid profile record");
            }
            String zone = Json.string(profile, "timezone", 80);
            if (!zone.equals("UTC") && (!ZoneId.getAvailableZoneIds().contains(zone) || !zone.contains("/") || zone.startsWith("SystemV/")))
                throw new IllegalStateException("Invalid profile timezone");
            return zone;
        } catch (ApiException invalid) { throw new IllegalStateException("Invalid profile record"); }
    }

    record Window(String key, Instant start, Instant end) {}
    static Window window(Instant checked, String timezone, boolean month) {
        ZoneId zone = ZoneId.of(timezone); LocalDate date = checked.atZone(zone).toLocalDate();
        LocalDate first = month ? date.withDayOfMonth(1) : date;
        LocalDate next = month ? first.plusMonths(1) : first.plusDays(1);
        Instant start = first.atStartOfDay(zone).toInstant(), end = next.atStartOfDay(zone).toInstant();
        outputInstant(checked); outputInstant(start); outputInstant(end);
        if (checked.isBefore(start) || !checked.isBefore(end)) throw new IllegalStateException("Invalid usage interval");
        return new Window(month ? YearMonth.from(date).toString() : date.toString(), start, end);
    }
    private static String outputInstant(Instant value) {
        if (value == null || value.atOffset(ZoneOffset.UTC).getYear() < 1 || value.atOffset(ZoneOffset.UTC).getYear() > 9999
                || value.getNano() % 1000 != 0) throw new IllegalStateException("Unrepresentable usage timestamp");
        return value.toString();
    }
}
