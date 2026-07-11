package com.stockman.scanner.model;

public class SignalEnums {
    public enum SignalType { BUY, SELL, STRONG_BUY, STRONG_SELL }
    public enum SignalStrength { WEAK, MODERATE, STRONG }
    public enum TradingStyle { SCALP, INTRADAY, SWING }
    public enum DataQuality { FRESH, STALE, PARTIAL, UNAVAILABLE }
    public enum AuthState { UNAUTHENTICATED, ACTIVE, DEGRADED }
    public enum DeliveryStatus { NEW, SENT, ENRICHED, FAILED, STALE }

    private SignalEnums() {}
}
