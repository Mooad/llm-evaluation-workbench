package com.aimastery.rag.api;

import com.aimastery.rag.api.dto.SearchQuery;
import com.aimastery.rag.api.dto.SearchResponse;
import com.aimastery.rag.search.SearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchQuery query) {
        int topK = query.topKOrDefault();
        return new SearchResponse(query.query(), topK,
                searchService.search(query.index(), query.query(),
                        new SearchService.Scope(query.documentIds(), query.collections(), query.sections()), topK));
    }
}
