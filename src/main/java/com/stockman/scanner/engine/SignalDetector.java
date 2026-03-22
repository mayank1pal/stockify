package com.stockman.scanner.engine;

import com.stockman.scanner.model.Candle;
import com.stockman.scanner.model.FundamentalData;
import com.stockman.scanner.model.IndicatorSnapshot;
import com.stockman.scanner.model.SignalEnums.*;
import com.stockman.scanner.model.TradeSignal;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Evaluates technical indicator snapshots against a set of buy/sell rules and
 * produces a {@link TradeSignal} when one or more rules trigger.
 *
 * <p>Rules are stateless; crossover detection relies on comparing the
 * {@code current} snapshot against the {@code previous} snapshot.
 */
@Slf4j
public class SignalDetector {

    /**
     * Evaluate indicators and generate a signal if conditions are met.
     *
     * @param instrumentToken Zerodha instrument token
     * @param symbol          Trading symbol (e.g. "RELIANCE")
     * @param candle          The candle that just closed
     * @param timeframe       Timeframe string ("1m", "5m", "15m")
     * @param current         Indicator snapshot computed after this candle
     * @param previous        Indicator snapshot from the prior candle
     * @param fundamentals    Fundamental data (available for future rule extensions)
     * @return an {@link Optional} containing the signal, or empty if no rules fired
     */
    public Optional<TradeSignal> evaluate(
            long instrumentToken, String symbol,
            Candle candle, String timeframe,
            IndicatorSnapshot current, IndicatorSnapshot previous,
            FundamentalData fundamentals) {

        if (!current.warmedUp()) {
            log.debug("Indicators not warmed up for {}; skipping signal evaluation", symbol);
            return Optional.empty();
        }

        List<String> buyReasons  = evaluateBuyRules(candle, current, previous);
        List<String> sellReasons = evaluateSellRules(candle, current, previous);

        // Prefer the direction with more rules firing; ties → no signal
        boolean hasBuy  = !buyReasons.isEmpty();
        boolean hasSell = !sellReasons.isEmpty();

        if (!hasBuy && !hasSell) {
            return Optional.empty();
        }

        // If both directions fire (unusual), pick the one with more rules.
        // If equal number, skip to avoid conflicting signals.
        List<String> triggerReasons;
        SignalType signalType;
        if (hasBuy && hasSell) {
            if (buyReasons.size() > sellReasons.size()) {
                triggerReasons = buyReasons;
                signalType = SignalType.BUY;
            } else if (sellReasons.size() > buyReasons.size()) {
                triggerReasons = sellReasons;
                signalType = SignalType.SELL;
            } else {
                log.debug("Equal buy/sell rules for {}; skipping ambiguous signal", symbol);
                return Optional.empty();
            }
        } else if (hasBuy) {
            triggerReasons = buyReasons;
            signalType = SignalType.BUY;
        } else {
            triggerReasons = sellReasons;
            signalType = SignalType.SELL;
        }

        SignalStrength strength = triggerReasons.size() >= 2
                ? SignalStrength.STRONG
                : SignalStrength.MODERATE;

        TradingStyle style = resolveStyle(timeframe);

        double entryPrice = candle.close();
        double atr = current.atr();

        double stopLoss;
        double target;
        if (signalType == SignalType.BUY) {
            stopLoss = entryPrice - 2.0 * atr;
            double risk = entryPrice - stopLoss;
            target   = entryPrice + 2.0 * risk;  // 1:2 risk-reward
        } else {
            stopLoss = entryPrice + 2.0 * atr;
            double risk = stopLoss - entryPrice;
            target   = entryPrice - 2.0 * risk;  // 1:2 risk-reward
        }

        String signalId = TradeSignal.buildSignalId(symbol, style, timeframe, candle.openTime(), signalType);

        TradeSignal signal = new TradeSignal(
                signalId, symbol, instrumentToken,
                signalType, strength, style,
                entryPrice, stopLoss, target,
                List.copyOf(triggerReasons),
                current,
                Instant.now()
        );

        log.info("Signal generated: {} {} {} [{}] reasons={}", signalType, strength, symbol, timeframe, triggerReasons);
        return Optional.of(signal);
    }

    // ── BUY rules ─────────────────────────────────────────────────────────────

    /**
     * Returns the list of human-readable reasons for BUY triggers.
     * Each fired rule appends one entry; the list is empty if none trigger.
     */
    private List<String> evaluateBuyRules(Candle candle,
                                           IndicatorSnapshot cur,
                                           IndicatorSnapshot prev) {
        List<String> reasons = new ArrayList<>();

        // Rule 1: EMA9 crosses above EMA21 + volume spike (rvol > 2.0)
        boolean ema9CrossedAbove = prev.ema9() < prev.ema21() && cur.ema9() >= cur.ema21();
        if (ema9CrossedAbove && cur.rvol() > 2.0) {
            reasons.add("EMA9 crossed above EMA21 with volume spike (rvol=" + String.format("%.2f", cur.rvol()) + ")");
        }

        // Rule 2: RSI crosses above 30 — oversold reversal
        if (prev.rsi() < 30.0 && cur.rsi() >= 30.0) {
            reasons.add("RSI crossed above 30 (prev=" + String.format("%.1f", prev.rsi())
                    + ", curr=" + String.format("%.1f", cur.rsi()) + ")");
        }

        // Rule 3: Price > VWAP + OBV rising
        if (candle.close() > cur.vwap() && cur.obv() > prev.obv()) {
            reasons.add("Price above VWAP with rising OBV");
        }

        // Rule 4: SuperTrend flips bullish
        if (!prev.superTrendBullish() && cur.superTrendBullish()) {
            reasons.add("SuperTrend flipped bullish");
        }

        return reasons;
    }

    // ── SELL rules ────────────────────────────────────────────────────────────

    /**
     * Returns the list of human-readable reasons for SELL triggers.
     */
    private List<String> evaluateSellRules(Candle candle,
                                            IndicatorSnapshot cur,
                                            IndicatorSnapshot prev) {
        List<String> reasons = new ArrayList<>();

        // Rule 1: EMA9 crosses below EMA21 + volume spike
        boolean ema9CrossedBelow = prev.ema9() > prev.ema21() && cur.ema9() <= cur.ema21();
        if (ema9CrossedBelow && cur.rvol() > 2.0) {
            reasons.add("EMA9 crossed below EMA21 with volume spike (rvol=" + String.format("%.2f", cur.rvol()) + ")");
        }

        // Rule 2: RSI crosses below 70 — overbought reversal
        if (prev.rsi() > 70.0 && cur.rsi() <= 70.0) {
            reasons.add("RSI crossed below 70 (prev=" + String.format("%.1f", prev.rsi())
                    + ", curr=" + String.format("%.1f", cur.rsi()) + ")");
        }

        // Rule 3: Price < VWAP + OBV falling
        if (candle.close() < cur.vwap() && cur.obv() < prev.obv()) {
            reasons.add("Price below VWAP with falling OBV");
        }

        // Rule 4: SuperTrend flips bearish
        if (prev.superTrendBullish() && !cur.superTrendBullish()) {
            reasons.add("SuperTrend flipped bearish");
        }

        return reasons;
    }

    // ── Style routing ─────────────────────────────────────────────────────────

    /**
     * Maps a timeframe string to a {@link TradingStyle}.
     * "1m" → SCALP, "5m" → INTRADAY, "15m" → SWING.
     * Unknown timeframes default to INTRADAY.
     */
    private TradingStyle resolveStyle(String timeframe) {
        return switch (timeframe) {
            case "1m"  -> TradingStyle.SCALP;
            case "15m" -> TradingStyle.SWING;
            default    -> TradingStyle.INTRADAY;
        };
    }
}
