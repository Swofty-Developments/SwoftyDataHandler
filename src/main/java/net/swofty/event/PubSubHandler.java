package net.swofty.event;

public interface PubSubHandler {
    void publish(String message);
    void subscribe(MessageHandler handler);
    void shutdown();

    @FunctionalInterface
    interface MessageHandler {
        void onMessage(String message);
    }
}
