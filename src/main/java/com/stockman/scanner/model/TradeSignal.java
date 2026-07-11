package com.stockman.scanner.model;

import com.stockman.scanner.model.SignalEnums.*;
import java.time.Instant;
import java.util.List;

public record TradeSignal(
    String signalId,
    String symbol, Long instrumentToken,
    SignalType type, SignalStrength strength,
    TradingStyle style,
    double entryPrice, double stopLoss, double target,
    List<String> triggerReasons,
    IndicatorSnapshot indicators,
    Instant generatedAt
) {
    public static String buildSignalId(String symbol, TradingStyle style,
            String timeframe, Instant candleClose, SignalType type) {
        return symbol + ":" + style + ":" + timeframe + ":" +
               candleClose.toEpochMilli() + ":" + type;
    }
}
