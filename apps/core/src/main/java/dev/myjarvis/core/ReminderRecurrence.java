package dev.myjarvis.core;

import java.time.*;
import java.time.zone.*;
import java.util.*;

/** Pure local-rule recurrence. The caller supplies all clock and authorization boundaries. */
final class ReminderRecurrence {
    record Rule(String kind, LocalDate start, LocalTime time, ZoneId zone, List<Integer> weekdays) {
        Rule { weekdays=List.copyOf(weekdays); }
    }
    record Slot(Instant instant, LocalDateTime requested, LocalDateTime resolved, ZoneOffset offset, String adjustment) {}
    static Slot resolve(LocalDate date, LocalTime time, ZoneId zone) {
        LocalDateTime requested=date.atTime(time);ZoneRules rules=zone.getRules();List<ZoneOffset> offsets=rules.getValidOffsets(requested);
        if(offsets.size()==1)return new Slot(requested.toInstant(offsets.getFirst()),requested,requested,offsets.getFirst(),"NONE");
        if(offsets.size()==2){ZoneOffset first=offsets.stream().min(Comparator.comparing(requested::toInstant)).orElseThrow();return new Slot(requested.toInstant(first),requested,requested,first,"FOLD_FIRST");}
        ZoneOffsetTransition transition=rules.getTransition(requested);
        if(transition==null||!transition.isGap())throw new ApiException(400,"UNSUPPORTED_LOCAL_TIME");
        // Do not use atZone(): that shifts 02:30 to 03:30 rather than the next valid 03:00.
        LocalDateTime resolved=transition.getDateTimeAfter();return new Slot(transition.getInstant(),requested,resolved,transition.getOffsetAfter(),"GAP_FORWARD");
    }
    static boolean includes(Rule rule,LocalDate date){
        if(date.isBefore(rule.start()))return false;
        return switch(rule.kind()){
            case "ONCE"->date.equals(rule.start());
            case "DAILY"->true;
            case "WEEKDAYS"->date.getDayOfWeek().getValue()<=5;
            case "WEEKLY","CUSTOM_DAYS"->rule.weekdays().contains(date.getDayOfWeek().getValue());
            default->throw ApiException.bad();
        };
    }
    static Slot next(Rule rule,Instant afterExclusive,Instant authorizationEnd){
        LocalDate local=afterExclusive.atZone(rule.zone()).toLocalDate().minusDays(1);
        LocalDate start=local.isAfter(rule.start())?local:rule.start();
        // A 30-day grant plus timezone transitions fits this hard bound. Never unbounded search.
        for(int i=0;i<34;i++){
            LocalDate date=start.plusDays(i);if(rule.kind().equals("ONCE")&&date.isAfter(rule.start()))return null;
            if(!includes(rule,date))continue;Slot slot=resolve(date,rule.time(),rule.zone());
            if(!slot.instant().isBefore(authorizationEnd))return null;
            if(slot.instant().isAfter(afterExclusive))return slot;
        }
        return null;
    }
    static boolean withinGrace(Instant planned,Instant now){return !planned.isAfter(now)&&!planned.isBefore(now.minusSeconds(3600));}
    private ReminderRecurrence(){}
}
