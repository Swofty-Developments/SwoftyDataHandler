package net.swofty.transaction;

public class TransactionAbortException extends RuntimeException {
    public TransactionAbortException() {
        super("Transaction aborted");
    }
}
