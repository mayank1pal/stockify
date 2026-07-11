package com.stockman.scanner.event;

import org.springframework.context.ApplicationEvent;
import java.util.Set;

public class ScanUniverseChangedEvent extends ApplicationEvent {
    private final Set<Long> instrumentTokens;

    public ScanUniverseChangedEvent(Object source, Set<Long> instrumentTokens) {
        super(source);
        this.instrumentTokens = instrumentTokens;
    }

    public Set<Long> getInstrumentTokens() { return instrumentTokens; }
}
