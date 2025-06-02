package org.honeyseeker;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
public class SearchResult {
    private String currentFile;
    private String currentEntry;
    private List<SearchEncounter> encounters = new ArrayList<>();
    private String bookFullText;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SearchEncounter {
        private String context;
        private int targetPosition;
        private int targetLen;
    }
}
