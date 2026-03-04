package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotRequest {
    @NotBlank(message = "Query is required")
    private String query;
    private String symbol;
    private List<ConversationMessage> conversationHistory;
}
