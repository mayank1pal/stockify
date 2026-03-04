package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsArticle {
    private String headline;
    private String summary;
    private String source;
    private String url;
    private long datetime;
    private String sentiment;
}
