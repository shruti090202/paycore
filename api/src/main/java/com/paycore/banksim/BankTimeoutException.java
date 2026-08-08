package com.paycore.banksim;

/**
 * The bank did not answer in time. This is NOT a decline: the bank may or may not have processed the request.
 * Callers must move the payment to an "unknown" state and resolve it later via {@link BankGateway#lookup}.
 */
public class BankTimeoutException extends Exception {

    public BankTimeoutException(String message) {
        super(message);
    }
}
