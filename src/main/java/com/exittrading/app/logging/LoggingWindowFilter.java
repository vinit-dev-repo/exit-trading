package com.exittrading.app.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Filters log events to the primary trading window (Mon-Fri, 09:15-15:30 IST).
 * Warn/Error logs are always allowed to avoid hiding critical issues.
 */
public class LoggingWindowFilter extends Filter<ILoggingEvent> {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 0);
    private static final LocalTime END = LocalTime.of(16, 0);

    @Override
    public FilterReply decide(ILoggingEvent event) {
        try {
            if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
                return FilterReply.ACCEPT;
            }
            ZonedDateTime nowIst = ZonedDateTime.now(IST);
            DayOfWeek dow = nowIst.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                return FilterReply.DENY;
            }
            LocalTime t = nowIst.toLocalTime();
            boolean within = !t.isBefore(START) && !t.isAfter(END);
            return within ? FilterReply.ACCEPT : FilterReply.DENY;
        } catch (Exception e) {
            return FilterReply.DENY;
        }
    }
}
