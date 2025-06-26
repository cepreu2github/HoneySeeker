package org.honeyseeker;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SearcherException extends Exception {
    private String currentFile;
    private String currentEntry;

    public SearcherException(String message, String currentFile, String currentEntry, Throwable cause) {
        super(message, cause);
        this.currentFile = currentFile;
        this.currentEntry = currentEntry;
    }
}
