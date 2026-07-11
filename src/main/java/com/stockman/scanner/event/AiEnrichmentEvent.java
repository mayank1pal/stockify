package com.stockman.scanner.event;

import com.stockman.scanner.model.AiEnrichment;
import org.springframework.context.ApplicationEvent;

public class AiEnrichmentEvent extends ApplicationEvent {
    private final AiEnrichment enrichment;

    public AiEnrichmentEvent(Object source, AiEnrichment enrichment) {
        super(source);
        this.enrichment = enrichment;
    }

    public AiEnrichment getEnrichment() { return enrichment; }
}
