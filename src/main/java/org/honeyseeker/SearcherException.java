package org.honeyseeker;

import lombok.Getter;

@Getter
public class SearcherException extends Exception {
    private final String currentFile;
    private final String currentEntry;

    public SearcherException(String message, String currentFile, String currentEntry, Throwable cause) {
        super(message, cause);
        this.currentFile = currentFile;
        this.currentEntry = currentEntry;
    }
}
