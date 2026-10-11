package dev.myjarvis.core;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReminderRecurrenceTest {
    ReminderRecurrence.Rule rule(String kind,String date,String time,String zone,Integer... days){return new ReminderRecurrence.Rule(kind,LocalDate.parse(date),LocalTime.parse(time),ZoneId.of(zone),List.of(days));}
    @Test void seoulConvertsExplicitLocalRuleToUtc(){var slot=ReminderRecurrence.resolve(LocalDate.parse("2030-01-01"),LocalTime.parse("09:00"),ZoneId.of("Asia/Seoul"));assertEquals(Instant.parse("2030-01-01T00:00:00Z"),slot.instant());assertEquals("NONE",slot.adjustment());}
    @Test void gapUsesNextValidInstantRatherThanShiftByGap(){var slot=ReminderRecurrence.resolve(LocalDate.parse("2030-03-10"),LocalTime.parse("02:30"),ZoneId.of("America/New_York"));assertEquals(Instant.parse("2030-03-10T07:00:00Z"),slot.instant());assertEquals(LocalDateTime.parse("2030-03-10T03:00:00"),slot.resolved());assertEquals("GAP_FORWARD",slot.adjustment());}
    @Test void foldUsesFirstInstantOnceThenOriginalNextDayRule(){var rule=rule("DAILY","2030-11-03","01:30","America/New_York");Instant end=Instant.parse("2030-11-06T00:00:00Z");var first=ReminderRecurrence.next(rule,Instant.parse("2030-11-03T00:00:00Z"),end);assertEquals(Instant.parse("2030-11-03T05:30:00Z"),first.instant());assertEquals("FOLD_FIRST",first.adjustment());assertEquals(Instant.parse("2030-11-04T06:30:00Z"),ReminderRecurrence.next(rule,first.instant(),end).instant());}
    @Test void gapDoesNotPermanentlyChangeDailyWallTime(){var rule=rule("DAILY","2030-03-10","02:30","America/New_York");var next=ReminderRecurrence.next(rule,Instant.parse("2030-03-10T07:00:00Z"),Instant.parse("2030-03-12T00:00:00Z"));assertEquals(Instant.parse("2030-03-11T06:30:00Z"),next.instant());assertEquals(LocalTime.parse("02:30"),next.resolved().toLocalTime());}
    @Test void nonHourGapResolvesToTransitionBoundary(){var slot=ReminderRecurrence.resolve(LocalDate.parse("2030-10-06"),LocalTime.parse("02:15"),ZoneId.of("Australia/Lord_Howe"));assertEquals(LocalDateTime.parse("2030-10-06T02:30:00"),slot.resolved());assertEquals("GAP_FORWARD",slot.adjustment());}
    @Test void skippedWholeDateDeduplicatesTheSameInstant(){var rule=rule("DAILY","2011-12-30","00:00","Pacific/Apia");var first=ReminderRecurrence.next(rule,Instant.parse("2011-12-29T00:00:00Z"),Instant.parse("2012-01-03T00:00:00Z"));assertEquals(LocalDateTime.parse("2011-12-31T00:00:00"),first.resolved());var next=ReminderRecurrence.next(rule,first.instant(),Instant.parse("2012-01-03T00:00:00Z"));assertEquals(LocalDateTime.parse("2012-01-01T00:00:00"),next.resolved());}
    @Test void weekdaysSkipWeekendAcrossYearBoundary(){var next=ReminderRecurrence.next(rule("WEEKDAYS","2027-12-31","09:00","UTC"),Instant.parse("2027-12-31T09:00:00Z"),Instant.parse("2028-01-10T00:00:00Z"));assertEquals(Instant.parse("2028-01-03T09:00:00Z"),next.instant());}
    @Test void weeklyAndCustomDaysUseIsoWeekdays(){var end=Instant.parse("2030-01-31T00:00:00Z");assertEquals(Instant.parse("2030-01-08T09:00:00Z"),ReminderRecurrence.next(rule("WEEKLY","2030-01-01","09:00","UTC",2),Instant.parse("2030-01-01T09:00:00Z"),end).instant());assertEquals(Instant.parse("2030-01-04T09:00:00Z"),ReminderRecurrence.next(rule("CUSTOM_DAYS","2030-01-01","09:00","UTC",1,5),Instant.parse("2030-01-01T00:00:00Z"),end).instant());}
    @Test void leapDayAndOnceTerminationAreExact(){var rule=rule("ONCE","2032-02-29","09:00","UTC");Instant due=Instant.parse("2032-02-29T09:00:00Z"),end=due.plusSeconds(86400);assertEquals(due,ReminderRecurrence.next(rule,due.minusSeconds(1),end).instant());assertNull(ReminderRecurrence.next(rule,due,end));}
    @Test void expiryIsExclusiveAndAncientStartIsBounded(){var rule=rule("DAILY","0001-01-01","09:00","UTC");Instant due=Instant.parse("2030-01-01T09:00:00Z");assertNull(ReminderRecurrence.next(rule,due.minusSeconds(1),due));assertEquals(due,ReminderRecurrence.next(rule,due.minusSeconds(1),due.plusSeconds(1)).instant());}
    @Test void graceChecksFullPrecisionAt3599And3600And3601(){Instant due=Instant.parse("2030-01-01T00:00:00Z");assertTrue(ReminderRecurrence.withinGrace(due,due.plusSeconds(3599)));assertTrue(ReminderRecurrence.withinGrace(due,due.plusSeconds(3600)));assertFalse(ReminderRecurrence.withinGrace(due,due.plusSeconds(3600).plusNanos(1)));assertFalse(ReminderRecurrence.withinGrace(due,due.plusSeconds(3601)));assertFalse(ReminderRecurrence.withinGrace(due,due.minusNanos(1)));}
    @Test void skippedLatenessUsesCeilingOnlyAfterGrace(){Instant due=Instant.parse("2030-01-01T00:00:00Z");assertEquals(3601,ScheduleWorker.lateness(due,due.plusSeconds(3600).plusNanos(1),true));assertEquals(3599,ScheduleWorker.lateness(due,due.plusSeconds(3599).plusNanos(1),false));}
    @Test void sharedRecurrenceFixtureMatchesEveryResolvedSlot() throws Exception {
        var vectors=Json.parse(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../../packages/contracts/fixtures/schedules-inbox/recurrence-vectors.json")));
        for(var vector:vectors){var local=LocalDateTime.parse(vector.get("local").asText());var actual=ReminderRecurrence.resolve(local.toLocalDate(),local.toLocalTime(),ZoneId.of(vector.get("timezone").asText()));assertEquals(Json.canonical(vector.get("expected")),Json.canonical(ScheduleService.slot(actual)),vector.get("name").asText());}
    }
    @Test void sharedCanonicalCommitmentsUseDomainSeparatedPrivateKeyCodec() throws Exception {
        var vectors=Json.parse(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../../packages/contracts/fixtures/schedules-inbox/canonical-vectors.json")));var file=java.nio.file.Files.createTempFile("reminder-public-fixture-",".bin");
        try {java.nio.file.Files.write(file,HexFormat.of().parseHex(vectors.get("test_vault_key_hex").asText()));java.nio.file.Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));var codec=new ReminderKeyCodec(file.toString());
            try{for(var vector:vectors.get("vectors")){String canonical=Json.canonical(vector.get("payload"));assertEquals(vector.get("canonical").asText(),canonical);assertEquals(vector.get("utf8_hex").asText(),HexFormat.of().formatHex(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));assertEquals(vector.get("commitment").asText(),codec.digest(UUID.fromString(vector.get("owner_id").asText()),vector.get("purpose").asText(),canonical));}}
            finally{codec.close();}
        }finally{java.nio.file.Files.deleteIfExists(file);}
    }
}
