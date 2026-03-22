package com.stockman.scanner.service;

import com.stockman.scanner.event.SignalEvent;
import com.stockman.scanner.model.IndicatorSnapshot;
import com.stockman.scanner.model.SignalEnums.SignalStrength;
import com.stockman.scanner.model.SignalEnums.SignalType;
import com.stockman.scanner.model.SignalEnums.TradingStyle;
import com.stockman.scanner.model.TradeSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Generates a small set of hardcoded demo {@link TradeSignal}s and publishes them as
 * {@link SignalEvent}s when there is no active Zerodha session.
 *
 * <p>This gives the UI a realistic look on first run / unauthenticated mode without
 * requiring a live market feed.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DemoSignalGenerator {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Publishes 5 representative demo signals covering all three trading styles and both
     * signal directions.
     */
    public void generateDemoSignals() {
        log.info("No Zerodha session — generating demo signals for UI preview");

        List<TradeSignal> demos = buildDemoSignals();
        for (TradeSignal signal : demos) {
            eventPublisher.publishEvent(new SignalEvent(this, signal, false));
            log.debug("Published demo signal: {} {} {}", signal.type(), signal.symbol(), signal.style());
        }

        log.info("Published {} demo signals", demos.size());
    }

    // ── Demo signal definitions ───────────────────────────────────────────────

    private static List<TradeSignal> buildDemoSignals() {
        Instant now = Instant.now();

        return List.of(
                // 1 — Strong intraday BUY on Reliance
                buildSignal("RELIANCE", 738561L,
                        SignalType.STRONG_BUY, SignalStrength.STRONG, TradingStyle.INTRADAY,
                        2945.50, 2920.00, 3000.00,
                        List.of("EMA9 crossed above EMA21", "MACD histogram turning positive", "RSI > 60"),
                        now.minusSeconds(120),
                        /* ema9 */ 2943.0, /* ema21 */ 2930.0, /* ema50 */ 2910.0,
                        /* ema200 */ 2800.0, /* rsi */ 63.5, /* macdLine */ 4.2,
                        /* macdSignal */ 2.1, /* macdHist */ 2.1,
                        /* stochK */ 72.0, /* stochD */ 68.0,
                        /* vwap */ 2938.0, /* superTrend */ 2915.0, /* stBullish */ true,
                        /* rvol */ 1.8, /* atr */ 18.5),

                // 2 — Scalp SELL on HDFC Bank (short squeeze fade)
                buildSignal("HDFCBANK", 341249L,
                        SignalType.SELL, SignalStrength.MODERATE, TradingStyle.SCALP,
                        1620.30, 1635.00, 1600.00,
                        List.of("Price rejected at intraday high", "Stochastic overbought crossover", "RVOL spike"),
                        now.minusSeconds(45),
                        1618.5, 1622.0, 1630.0,
                        1580.0, 74.2, -1.5,
                        -2.8, 1.3,
                        82.0, 79.0,
                        1615.0, 1640.0, false,
                        2.3, 8.2),

                // 3 — Swing BUY on TCS (breakout)
                buildSignal("TCS", 2953217L,
                        SignalType.BUY, SignalStrength.STRONG, TradingStyle.SWING,
                        3870.00, 3810.00, 4020.00,
                        List.of("Weekly resistance breakout", "EMA200 support intact", "Rising RSI from oversold"),
                        now.minusSeconds(300),
                        3865.0, 3850.0, 3820.0,
                        3750.0, 58.0, 6.4,
                        4.1, 2.3,
                        55.0, 52.0,
                        3855.0, 3800.0, true,
                        1.6, 30.0),

                // 4 — Intraday SELL on Infosys (breakdown)
                buildSignal("INFY", 408065L,
                        SignalType.SELL, SignalStrength.MODERATE, TradingStyle.INTRADAY,
                        1755.00, 1785.00, 1710.00,
                        List.of("EMA21 crossed below EMA50", "MACD bearish crossover", "Below VWAP"),
                        now.minusSeconds(90),
                        1757.0, 1768.0, 1780.0,
                        1810.0, 41.5, -3.2,
                        -1.8, -1.4,
                        38.0, 42.0,
                        1770.0, 1790.0, false,
                        1.4, 14.0),

                // 5 — Scalp STRONG_BUY on Nifty50 ETF (momentum burst)
                buildSignal("NIFTYBEES", 11915394L,
                        SignalType.STRONG_BUY, SignalStrength.STRONG, TradingStyle.SCALP,
                        242.80, 240.00, 246.50,
                        List.of("SuperTrend flipped bullish", "RSI breakout above 60", "Volume surge"),
                        now.minusSeconds(20),
                        242.5, 241.0, 239.0,
                        235.0, 61.8, 0.8,
                        0.3, 0.5,
                        62.0, 58.0,
                        241.5, 240.2, true,
                        3.1, 1.1)
        );
    }

    @SuppressWarnings("java:S107") // Many params needed to build a realistic snapshot
    private static TradeSignal buildSignal(
            String symbol, long token,
            SignalType type, SignalStrength strength, TradingStyle style,
            double entry, double stop, double target,
            List<String> reasons,
            Instant generatedAt,
            double ema9, double ema21, double ema50, double ema200,
            double rsi, double macdLine, double macdSignal, double macdHist,
            double stochK, double stochD,
            double vwap, double superTrend, boolean stBullish,
            double rvol, double atr) {

        String timeframe = switch (style) {
            case SCALP    -> "1m";
            case INTRADAY -> "5m";
            case SWING    -> "15m";
        };

        String signalId = symbol + ":" + style + ":" + timeframe + ":"
                + generatedAt.toEpochMilli() + ":" + type;

        IndicatorSnapshot indicators = new IndicatorSnapshot(
                ema9, ema21, ema50, ema200,
                rsi,
                macdLine, macdSignal, macdHist,
                stochK, stochD,
                vwap, superTrend, stBullish,
                /* obv */ 0.0,
                rvol, atr,
                /* warmedUp */ true
        );

        return new TradeSignal(
                signalId, symbol, token,
                type, strength, style,
                entry, stop, target,
                reasons,
                indicators,
                generatedAt
        );
    }
}
