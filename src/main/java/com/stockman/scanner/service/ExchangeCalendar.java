package com.stockman.scanner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockman.config.ScannerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.*;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
public class ExchangeCalendar {
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Set<LocalDate> holidays = new HashSet<>();
    private final Set<LocalDate> specialSessionDates = new HashSet<>();

    @Autowired
    public ExchangeCalendar(ScannerConfig scannerConfig) {
        loadHolidays(scannerConfig.getHolidayCalendar());
    }

    // Constructor for tests (no Spring DI)
    ExchangeCalendar(String holidayCalendarPath) {
        loadHolidays(holidayCalendarPath);
    }

    private void loadHolidays(String path) {
        try {
            InputStream is = new DefaultResourceLoader().getResource(path).getInputStream();
            JsonNode root = new ObjectMapper().readTree(is);
            root.get("holidays").forEach(node ->
                holidays.add(LocalDate.parse(node.asText()))
            );
            if (root.has("specialSessions")) {
                root.get("specialSessions").forEach(node ->
                    specialSessionDates.add(LocalDate.parse(node.get("date").asText()))
                );
            }
            log.info("Loaded {} holidays, {} special sessions from {}",
                holidays.size(), specialSessionDates.size(), path);
        } catch (Exception e) {
            log.warn("Failed to load holiday calendar from {}: {}", path, e.getMessage());
        }
    }

    public boolean isTradingDay(LocalDate date) {
        if (specialSessionDates.contains(date)) return true;
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        return !holidays.contains(date);
    }

    public boolean isWithinMarketHours(LocalTime time) {
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    public LocalTime getMarketOpen() { return MARKET_OPEN; }
    public LocalTime getMarketClose() { return MARKET_CLOSE; }
    public ZoneId getTimeZone() { return IST; }
}
