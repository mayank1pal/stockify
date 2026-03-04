package com.stockman.service;

import java.util.concurrent.CompletableFuture;

public interface AIModelService {

    String getName();

    String analyze(String systemPrompt, String userPrompt);

    CompletableFuture<String> analyzeAsync(String systemPrompt, String userPrompt);

    boolean isAvailable();
}
