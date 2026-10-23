package com.aimastery.rag.index;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Les index configures. Le premier est celui par defaut : recherche, evaluation, anciens documents. */
public class SearchIndexes {

    private final List<SearchIndex> indexes;

    public SearchIndexes(List<SearchIndex> indexes) {
        if (indexes.isEmpty()) {
            throw new IllegalArgumentException("Au moins un index doit etre configure (rag.indexes)");
        }
        Set<String> names = new HashSet<>();
        Set<String> collections = new HashSet<>();
        for (SearchIndex index : indexes) {
            if (!names.add(index.name())) {
                throw new IllegalArgumentException("Index declare deux fois : " + index.name());
            }
            // Deux strategies dans une meme collection melangeraient leurs chunks : la comparaison ne voudrait plus rien dire.
            if (!collections.add(index.collection())) {
                throw new IllegalArgumentException("Collection partagee par deux index : " + index.collection());
            }
        }
        this.indexes = List.copyOf(indexes);
    }

    public List<SearchIndex> all() {
        return indexes;
    }

    public List<String> names() {
        return indexes.stream().map(SearchIndex::name).toList();
    }

    public SearchIndex defaultIndex() {
        return indexes.get(0);
    }

    /** @param name null ou vide = l'index par defaut */
    public SearchIndex get(String name) {
        if (name == null || name.isBlank()) {
            return defaultIndex();
        }
        return indexes.stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow(() -> new UnknownIndexException(name, names()));
    }
}
