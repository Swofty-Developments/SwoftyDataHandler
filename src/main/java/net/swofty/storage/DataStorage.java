package net.swofty.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface DataStorage extends AutoCloseable {
    byte[] load(String type, String id);
    CompletionStage<SaveResult> save(String type, String id, byte[] data);

    default VersionedData loadVersioned(String type, String id) {
        return new VersionedData(load(type, id), 0L);
    }

    default CompletionStage<BatchSaveResult> saveBatch(List<SaveRequest> requests) {
        CompletableFuture<BatchSaveResult> result = CompletableFuture.completedFuture(new BatchSaveResult(List.of()));
        for (SaveRequest request : requests) {
            result = result.thenCompose(previous -> save(request.key().type(), request.key().id(), request.data())
                    .thenApply(saved -> {
                        List<SaveResult> all = new ArrayList<>(previous.results());
                        all.add(saved);
                        return new BatchSaveResult(all);
                    })).toCompletableFuture();
        }
        return result;
    }

    default CompletionStage<BatchSaveResult> saveSnapshot(StorageSnapshot snapshot) {
        return saveBatch(snapshot.documents());
    }
    List<String> listIds(String type);
    void delete(String type, String id);
    boolean exists(String type, String id);
    @Override default void close() {}
}
