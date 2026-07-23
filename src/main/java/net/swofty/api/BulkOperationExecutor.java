package net.swofty.api;

import net.swofty.*;
import net.swofty.event.EventBus;
import net.swofty.storage.DataStorage;
import net.swofty.storage.LeaderboardIndex;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public class BulkOperationExecutor {
    private final PlayerDataManager playerData;
    private final LinkedDataManager linkedData;
    private final DataStorage storage;
    private final EventBus eventBus;

    public BulkOperationExecutor(PlayerDataManager playerData, LinkedDataManager linkedData,
                                  DataStorage storage, EventBus eventBus) {
        this.playerData = playerData;
        this.linkedData = linkedData;
        this.storage = storage;
        this.eventBus = eventBus;
    }

    public <T extends Comparable<T>> List<LeaderboardEntry<T>> getTop(PlayerField<T> field, int limit) {
        LeaderboardIndex index = indexFor(field);
        if (index != null) {
            return fromIndex(field, index, 0, limit - 1);
        }
        return getTop(field, limit, Comparator.reverseOrder());
    }

    // The sorted index only exists when the backend maintains one AND the field is tracked;
    // otherwise we fall back to the scan below, so this is purely an accelerator.
    private LeaderboardIndex indexFor(PlayerField<?> field) {
        if (storage instanceof LeaderboardIndex index && playerData.isLeaderboardTracked(field.fullKey())) {
            return index;
        }
        return null;
    }

    private <T> List<LeaderboardEntry<T>> fromIndex(PlayerField<T> field, LeaderboardIndex index,
                                                    int start, int endInclusive) {
        List<LeaderboardIndex.ScoreEntry> range = index.scoreRange(field.fullKey(), start, endInclusive, true);
        List<LeaderboardEntry<T>> result = new ArrayList<>(range.size());
        int rank = start + 1;
        for (LeaderboardIndex.ScoreEntry entry : range) {
            UUID id = UUID.fromString(entry.id());
            result.add(new LeaderboardEntry<>(id, playerData.getFieldValue(id, field), rank++));
        }
        return result;
    }

    public <T> List<LeaderboardEntry<T>> getTop(PlayerField<T> field, int limit, Comparator<T> comparator) {
        List<Map.Entry<UUID, T>> entries = getAllPlayerValues(field);
        entries.sort((a, b) -> comparator.compare(a.getValue(), b.getValue()));
        List<LeaderboardEntry<T>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            Map.Entry<UUID, T> e = entries.get(i);
            result.add(new LeaderboardEntry<>(e.getKey(), e.getValue(), i + 1));
        }
        return result;
    }

    public <T extends Comparable<T>> Page<LeaderboardEntry<T>> getTopPaged(PlayerField<T> field, int page, int pageSize) {
        LeaderboardIndex index = indexFor(field);
        if (index != null) {
            long total = index.leaderboardSize(field.fullKey());
            int totalPages = (int) Math.ceil((double) total / pageSize);
            int start = (page - 1) * pageSize;
            List<LeaderboardEntry<T>> content = fromIndex(field, index, start, start + pageSize - 1);
            return new Page<>(content, page, totalPages, total);
        }
        List<Map.Entry<UUID, T>> entries = getAllPlayerValues(field);
        entries.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        long total = entries.size();
        int totalPages = (int) Math.ceil((double) total / pageSize);
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, entries.size());

        List<LeaderboardEntry<T>> content = new ArrayList<>();
        for (int i = start; i < end; i++) {
            Map.Entry<UUID, T> e = entries.get(i);
            content.add(new LeaderboardEntry<>(e.getKey(), e.getValue(), i + 1));
        }
        return new Page<>(content, page, totalPages, total);
    }

    public <T> List<UUID> query(PlayerField<T> field, Predicate<T> filter) {
        return getAllPlayerValues(field).stream()
                .filter(e -> filter.test(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    public <T> int count(PlayerField<T> field, Predicate<T> filter) {
        return (int) getAllPlayerValues(field).stream()
                .filter(e -> filter.test(e.getValue()))
                .count();
    }

    public <T> int updateAll(PlayerField<T> field, UnaryOperator<T> updater) {
        return updateWhere(field, t -> true, updater);
    }

    public <T> int updateWhere(PlayerField<T> field, Predicate<T> filter, UnaryOperator<T> updater) {
        int count = 0;
        for (String id : storage.listIds("players")) {
            UUID playerId = UUID.fromString(id);
            synchronized (playerData.getLock(playerId)) {
                T value = playerData.getFieldValue(playerId, field);
                if (filter.test(value)) {
                    T newValue = updater.apply(value);
                    Validation.validate(field, newValue);
                    playerData.setFieldValue(playerId, field, newValue);
                    eventBus.firePlayerDataChanged(field, playerId, value, newValue);
                    count++;
                }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    public <K, T extends Comparable<T>> List<LeaderboardEntry<T>> getTopLinked(LinkedField<K, T> field, int limit) {
        String linkTypeName = field.linkType().name();
        List<String> ids = linkedData.listLinkedIds(linkTypeName);
        List<Map.Entry<UUID, T>> entries = new ArrayList<>();

        for (String id : ids) {
            T value = linkedData.getFieldValue(linkTypeName, id, field);
            UUID entryId;
            try {
                entryId = UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                entryId = UUID.nameUUIDFromBytes(id.getBytes());
            }
            entries.add(Map.entry(entryId, value));
        }

        entries.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        List<LeaderboardEntry<T>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            Map.Entry<UUID, T> e = entries.get(i);
            result.add(new LeaderboardEntry<>(e.getKey(), e.getValue(), i + 1));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public <K, T> List<K> queryLinked(LinkedField<K, T> field, Predicate<T> filter) {
        String linkTypeName = field.linkType().name();
        List<String> ids = linkedData.listLinkedIds(linkTypeName);
        List<K> result = new ArrayList<>();

        for (String id : ids) {
            T value = linkedData.getFieldValue(linkTypeName, id, field);
            if (filter.test(value)) {
                try {
                    result.add((K) UUID.fromString(id));
                } catch (IllegalArgumentException e) {
                    result.add((K) id);
                }
            }
        }
        return result;
    }

    private <T> List<Map.Entry<UUID, T>> getAllPlayerValues(PlayerField<T> field) {
        List<Map.Entry<UUID, T>> entries = new ArrayList<>();
        for (String id : storage.listIds("players")) {
            UUID playerId = UUID.fromString(id);
            T value = playerData.getFieldValue(playerId, field);
            entries.add(Map.entry(playerId, value));
        }
        return entries;
    }
}
