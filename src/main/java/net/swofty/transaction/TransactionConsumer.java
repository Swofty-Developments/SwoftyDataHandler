package net.swofty.transaction;

@FunctionalInterface
public interface TransactionConsumer {
    void accept(Transaction tx);
}
