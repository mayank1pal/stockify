package com.stockman.scanner.event;

import com.stockman.scanner.model.TradeSignal;
import org.springframework.context.ApplicationEvent;

public class SignalEvent extends ApplicationEvent {
    private final TradeSignal signal;
    private final boolean aiPending;

    public SignalEvent(Object source, TradeSignal signal, boolean aiPending) {
        super(source);
        this.signal = signal;
        this.aiPending = aiPending;
    }

    public TradeSignal getSignal() { return signal; }
    public boolean isAiPending() { return aiPending; }
}
