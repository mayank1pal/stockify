package com.stockman.scanner.model;

import com.stockman.scanner.model.SignalEnums.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class TradeSignalTest {
    @Test
    void buildSignalId_produceDeterministicId() {
        Instant candleClose = Instant.parse("2026-03-22T04:15:00Z");
        String id1 = TradeSignal.buildSignalId("RELIANCE", TradingStyle.INTRADAY,
                "5m", candleClose, SignalType.BUY);
        String id2 = TradeSignal.buildSignalId("RELIANCE", TradingStyle.INTRADAY,
                "5m", candleClose, SignalType.BUY);
        assertEquals(id1, id2);
        assertTrue(id1.contains("RELIANCE"));
        assertTrue(id1.contains("INTRADAY"));
    }

    @Test
    void buildSignalId_differentInputsProduceDifferentIds() {
        Instant candleClose = Instant.parse("2026-03-22T04:15:00Z");
        String buy = TradeSignal.buildSignalId("RELIANCE", TradingStyle.INTRADAY,
                "5m", candleClose, SignalType.BUY);
        String sell = TradeSignal.buildSignalId("RELIANCE", TradingStyle.INTRADAY,
                "5m", candleClose, SignalType.SELL);
        assertNotEquals(buy, sell);
    }
}
