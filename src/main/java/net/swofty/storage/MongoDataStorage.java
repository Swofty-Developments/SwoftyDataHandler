package net.swofty.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.Binary;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;

public class MongoDataStorage implements DataStorage {
    private final MongoDatabase database;
    private final MongoClient ownedClient;

    public MongoDataStorage(MongoClient client, String databaseName) {
        this.database = client.getDatabase(databaseName);
        this.ownedClient = client;
    }

    public MongoDataStorage(MongoDatabase database) {
        this.database = database;
        this.ownedClient = null;
    }

    private MongoCollection<Document> collection(String type) {
        return database.getCollection(type);
    }

    @Override
    public byte[] load(String type, String id) {
        Document doc = collection(type).find(Filters.eq("_id", id)).first();
        if (doc == null) return null;
        Binary binary = doc.get("data", Binary.class);
        return binary == null ? null : binary.getData();
    }

    @Override
    public CompletionStage<SaveResult> save(String type, String id, byte[] data) {
        Document doc = collection(type).findOneAndUpdate(Filters.eq("_id", id),
                Updates.combine(Updates.set("data", new Binary(data)), Updates.inc("version", 1L)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        long version = doc == null ? 1L : ((Number) doc.getOrDefault("version", 1L)).longValue();
        return CompletableFuture.completedFuture(SaveResult.saved(type, id, version, data.length));
    }

    @Override
    public VersionedData loadVersioned(String type, String id) {
        Document doc = collection(type).find(Filters.eq("_id", id)).first();
        if (doc == null) return new VersionedData(null, 0);
        Binary binary = doc.get("data", Binary.class);
        Number version = (Number) doc.getOrDefault("version", 0L);
        return new VersionedData(binary == null ? null : binary.getData(), version.longValue());
    }

    @Override
    public List<String> listIds(String type) {
        List<String> ids = new ArrayList<>();
        for (Document doc : collection(type).find()) {
            ids.add(doc.getString("_id"));
        }
        return ids;
    }

    @Override
    public void delete(String type, String id) {
        collection(type).deleteOne(Filters.eq("_id", id));
    }

    @Override
    public boolean exists(String type, String id) {
        return collection(type).countDocuments(Filters.eq("_id", id)) > 0;
    }

    @Override public void close() { if (ownedClient != null) ownedClient.close(); }
}
