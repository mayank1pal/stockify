package com.stockman.scanner.engine;

import com.stockman.config.ScannerConfig;
import com.stockman.scanner.model.SignalEnums.*;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages per-symbol/style/timeframe signal state to prevent duplicate
 * or premature re-emission of signals.
 *
 * <p>State machine per key ({@code symbol:style:timeframe}):
 * <pre>
 *   NEUTRAL → BUY_ACTIVE / SELL_ACTIVE → COOLDOWN → NEUTRAL
 *              ↕  (direct reversal at >= MODERATE strength)
 *           SELL_ACTIVE / BUY_ACTIVE
 * </pre>
 *
 * <ul>
 *   <li>Opposite-direction signals bypass cooldown only when strength >= MODERATE.</li>
 *   <li>States older than 2× cooldown period auto-expire to NEUTRAL.</li>
 *   <li>Uses {@link AtomicReference} + CAS for thread-safe state transitions.</li>
 * </ul>
 */
@Slf4j
public class SignalCooldownManager {

    // ── Internal state ────────────────────────────────────────────────────────

    private enum CooldownState { NEUTRAL, BUY_ACTIVE, SELL_ACTIVE, COOLDOWN }

    private record SignalState(CooldownState state, Instant recordedAt, SignalType lastType) {}

    private final ConcurrentHashMap<String, AtomicReference<SignalState>> stateMap =
            new ConcurrentHashMap<>();

    private final ScannerConfig config;

    public SignalCooldownManager(ScannerConfig config) {
        this.config = config;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if a signal with the given parameters may be emitted.
     * This method is read-only; it does NOT transition state.
     */
    public boolean canEmitSignal(String symbol, TradingStyle style, String timeframe,
                                  SignalType type, SignalStrength strength) {
        String key = buildKey(symbol, style, timeframe);
        AtomicReference<SignalState> ref = stateMap.get(key);
        if (ref == null) {
            return true; // No state → NEUTRAL → allowed
        }

        SignalState state = ref.get();
        Duration cooldown = cooldownDuration(style);

        // Auto-expire states older than 2× cooldown
        if (isExpired(state, cooldown)) {
            return true;
        }

        return switch (state.state()) {
            case NEUTRAL  -> true;
            case COOLDOWN -> isOppositeAndStrong(state.lastType(), type, strength);
            case BUY_ACTIVE  -> {
                if (type == SignalType.BUY) yield false;  // duplicate
                yield isModerateOrStronger(strength);     // SELL reversal
            }
            case SELL_ACTIVE -> {
                if (type == SignalType.SELL) yield false; // duplicate
                yield isModerateOrStronger(strength);     // BUY reversal
            }
        };
    }

    /**
     * Records that a signal was emitted, transitioning the state machine.
     * Only call this after {@link #canEmitSignal} returns {@code true} and the
     * signal has actually been dispatched.
     */
    public void recordSignal(String symbol, TradingStyle style, String timeframe,
                              SignalType type, SignalStrength strength) {
        String key = buildKey(symbol, style, timeframe);
        AtomicReference<SignalState> ref = stateMap.computeIfAbsent(
                key, k -> new AtomicReference<>(new SignalState(CooldownState.NEUTRAL, Instant.now(), null)));

        // CAS loop for safe transitions
        while (true) {
            SignalState current = ref.get();
            SignalState next = computeNextState(current, type, style);
            if (ref.compareAndSet(current, next)) {
                log.debug("CooldownManager: key={} {} → {}", key, current.state(), next.state());
                break;
            }
        }
    }

    // ── State transition logic ────────────────────────────────────────────────

    private SignalState computeNextState(SignalState current, SignalType type, TradingStyle style) {
        Instant now = Instant.now();
        CooldownState newState = (type == SignalType.BUY)
                ? CooldownState.BUY_ACTIVE
                : CooldownState.SELL_ACTIVE;
        return new SignalState(newState, now, type);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isExpired(SignalState state, Duration cooldown) {
        if (state.state() == CooldownState.NEUTRAL) return false;
        Duration age = Duration.between(state.recordedAt(), Instant.now());
        return age.compareTo(cooldown.multipliedBy(2)) > 0;
    }

    private boolean isOppositeAndStrong(SignalType lastType, SignalType newType, SignalStrength strength) {
        if (lastType == null) return true;
        boolean opposite = (lastType == SignalType.BUY && newType == SignalType.SELL)
                || (lastType == SignalType.SELL && newType == SignalType.BUY);
        return opposite && isModerateOrStronger(strength);
    }

    private boolean isModerateOrStronger(SignalStrength strength) {
        return strength == SignalStrength.MODERATE || strength == SignalStrength.STRONG;
    }

    private Duration cooldownDuration(TradingStyle style) {
        ScannerConfig.Cooldown cd = config.getCooldown();
        return switch (style) {
            case SCALP    -> Duration.ofMinutes(cd.getScalpMinutes());
            case INTRADAY -> Duration.ofMinutes(cd.getIntradayMinutes());
            case SWING    -> Duration.ofMinutes(cd.getSwingMinutes());
        };
    }

    private String buildKey(String symbol, TradingStyle style, String timeframe) {
        return symbol + ":" + style + ":" + timeframe;
    }
}
