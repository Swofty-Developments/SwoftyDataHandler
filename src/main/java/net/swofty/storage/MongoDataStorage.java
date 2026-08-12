package net.swofty.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.Binary;

import java.util.ArrayList;
import java.util.List;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.conversions.Bson;

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
    public void save(String type, String id, byte[] data) {
        collection(type).findOneAndUpdate(Filters.eq("_id", id), writeAndBump(data),
                versionOnly().upsert(true));
    }

    @Override
    public SaveResult saveIfVersion(String type, String id, byte[] data, long expectedVersion) {
        if (expectedVersion == VersionedData.ANY_VERSION) {
            Document overwritten = collection(type).findOneAndUpdate(Filters.eq("_id", id), writeAndBump(data),
                    versionOnly().upsert(true));
            return SaveResult.saved(type, id, overwritten == null ? 1L : versionOf(overwritten));
        }
        Bson filter = expectedVersion == VersionedData.UNVERSIONED
                // A document that predates versioning, or one that does not exist yet, is version 0.
                ? Filters.and(Filters.eq("_id", id),
                        Filters.or(Filters.eq("version", 0L), Filters.exists("version", false)))
                : Filters.and(Filters.eq("_id", id), Filters.eq("version", expectedVersion));
        Document updated;
        try {
            updated = collection(type).findOneAndUpdate(filter, writeAndBump(data),
                    versionOnly().upsert(expectedVersion == VersionedData.UNVERSIONED));
        } catch (MongoWriteException lostTheInsertRace) {
            return SaveResult.conflict(type, id, storedVersion(type, id));
        }
        if (updated == null) return SaveResult.conflict(type, id, storedVersion(type, id));
        return SaveResult.saved(type, id, versionOf(updated));
    }

    @Override
    public VersionedData loadVersioned(String type, String id) {
        Document doc = collection(type).find(Filters.eq("_id", id)).first();
        if (doc == null) return new VersionedData(null, VersionedData.UNVERSIONED);
        Binary binary = doc.get("data", Binary.class);
        return new VersionedData(binary == null ? null : binary.getData(), versionOf(doc));
    }

    private static Bson writeAndBump(byte[] data) {
        return Updates.combine(Updates.set("data", new Binary(data)), Updates.inc("version", 1L));
    }

    // The document body is the whole point of the collection; never ship it back just to read a
    // counter off it.
    private static FindOneAndUpdateOptions versionOnly() {
        return new FindOneAndUpdateOptions()
                .returnDocument(ReturnDocument.AFTER)
                .projection(Projections.include("version"));
    }

    private long storedVersion(String type, String id) {
        Document doc = collection(type).find(Filters.eq("_id", id))
                .projection(Projections.include("version")).first();
        return doc == null ? VersionedData.UNVERSIONED : versionOf(doc);
    }

    private static long versionOf(Document doc) {
        Object version = doc.get("version");
        return version instanceof Number number ? number.longValue() : VersionedData.UNVERSIONED;
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
