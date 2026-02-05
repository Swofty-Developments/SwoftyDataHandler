package net.swofty.event;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

public class RedisPubSubHandler implements PubSubHandler {
    private final JedisPool pool;
    private final String channel;

    private Thread subscriberThread;
    private volatile JedisPubSub activeSub;
    private volatile boolean running = true;

    public RedisPubSubHandler(JedisPool pool, String channel) {
        this.pool = pool;
        this.channel = channel;
    }

    public RedisPubSubHandler(JedisPool pool) {
        this(pool, "swofty:events");
    }

    @Override
    public void publish(String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, message);
        }
    }

    @Override
    public void subscribe(MessageHandler handler) {
        subscriberThread = new Thread(() -> {
            while (running) {
                try (Jedis jedis = pool.getResource()) {
                    activeSub = new JedisPubSub() {
                        @Override
                        public void onMessage(String ch, String msg) {
                            handler.onMessage(msg);
                        }
                    };
                    jedis.subscribe(activeSub, channel);
                } catch (Exception e) {
                    if (running) {
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                    }
                }
            }
        }, "swofty-pubsub-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    @Override
    public void shutdown() {
        running = false;
        if (activeSub != null) {
            try { activeSub.unsubscribe(); } catch (Exception ignored) {}
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
    }
}
