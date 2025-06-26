package org.honeyseeker;

import java.util.List;

public interface Logger {
    void logInfo(List<String> lines);
    void logInfo(String line);
    void logWarn(List<String> lines);
    void logWarn(String line);
}
