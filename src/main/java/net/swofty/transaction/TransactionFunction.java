package net.swofty.transaction;

@FunctionalInterface
public interface TransactionFunction<R> {
    R apply(Transaction tx);
}
