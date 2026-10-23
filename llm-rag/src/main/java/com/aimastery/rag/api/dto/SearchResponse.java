package com.aimastery.rag.api.dto;

import com.aimastery.rag.search.SearchHit;
import java.util.List;

public record SearchResponse(String query, int topK, List<SearchHit> results) {
}
