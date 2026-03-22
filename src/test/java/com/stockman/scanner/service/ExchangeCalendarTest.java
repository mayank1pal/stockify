package com.stockman.scanner.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeCalendarTest {
    private ExchangeCalendar calendar;

    @BeforeEach
    void setUp() {
        calendar = new ExchangeCalendar("classpath:holidays.json");
    }

    @Test
    void isTradingDay_weekday_notHoliday_returnsTrue() {
        assertTrue(calendar.isTradingDay(LocalDate.of(2026, 3, 23))); // Monday
    }

    @Test
    void isTradingDay_weekend_returnsFalse() {
        assertFalse(calendar.isTradingDay(LocalDate.of(2026, 3, 22))); // Sunday
    }

    @Test
    void isTradingDay_holiday_returnsFalse() {
        assertFalse(calendar.isTradingDay(LocalDate.of(2026, 1, 26))); // Republic Day
    }

    @Test
    void isTradingDay_muhuratTrading_returnsTrue() {
        // 2026-11-18 is both a holiday AND a Muhurat special session — should return true
        assertTrue(calendar.isTradingDay(LocalDate.of(2026, 11, 18)));
    }

    @Test
    void isWithinMarketHours_duringSession_returnsTrue() {
        assertTrue(calendar.isWithinMarketHours(LocalTime.of(10, 30)));
    }

    @Test
    void isWithinMarketHours_beforeOpen_returnsFalse() {
        assertFalse(calendar.isWithinMarketHours(LocalTime.of(8, 0)));
    }

    @Test
    void isWithinMarketHours_afterClose_returnsFalse() {
        assertFalse(calendar.isWithinMarketHours(LocalTime.of(16, 0)));
    }
}
