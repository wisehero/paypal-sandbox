package dev.wisehero.paypalsandbox.fault;

public class SimulatedDbFailureException extends RuntimeException {

    public SimulatedDbFailureException(String message) {
        super(message);
    }
}
