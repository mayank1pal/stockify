package com.stockman.scanner.event;

import org.springframework.context.ApplicationEvent;

public class SessionExpiryEvent extends ApplicationEvent {
    private final String reason;

    public SessionExpiryEvent(Object source, String reason) {
        super(source);
        this.reason = reason;
    }

    public String getReason() { return reason; }
}
