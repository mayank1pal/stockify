package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentInfo {
    private String type;
    private String name;
    private String description;
    private String icon;
    private String[] focusAreas;
}
