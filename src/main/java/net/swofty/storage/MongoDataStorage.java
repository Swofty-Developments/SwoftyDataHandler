package net.swofty.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.types.Binary;

import java.util.ArrayList;
import java.util.List;

public class MongoDataStorage implements DataStorage {
    private final MongoDatabase database;

    public MongoDataStorage(MongoClient client, String databaseName) {
        this.database = client.getDatabase(databaseName);
    }

    public MongoDataStorage(MongoDatabase database) {
        this.database = database;
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
        Document doc = new Document("_id", id).append("data", new Binary(data));
        collection(type).replaceOne(Filters.eq("_id", id), doc, new ReplaceOptions().upsert(true));
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
}
