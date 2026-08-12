package net.swofty.storage;

import java.util.List;

public record BatchSaveResult(List<SaveResult> results) {
    public BatchSaveResult { results = List.copyOf(results); }
    public int savedCount() { return (int) results.stream().filter(SaveResult::saved).count(); }
    public int bytesWritten() { return results.stream().mapToInt(SaveResult::bytesWritten).sum(); }
}
