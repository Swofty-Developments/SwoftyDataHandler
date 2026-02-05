package net.swofty.event;

import redis.clients.jedis.JedisPool;

/**
 * PubSubHandler implementation for KeyDB.
 * KeyDB is wire-compatible with Redis, so this uses Jedis under the hood.
 */
public class KeyDBPubSubHandler extends RedisPubSubHandler {
    public KeyDBPubSubHandler(JedisPool pool, String channel) {
        super(pool, channel);
    }

    public KeyDBPubSubHandler(JedisPool pool) {
        super(pool, "swofty:events");
    }
}
