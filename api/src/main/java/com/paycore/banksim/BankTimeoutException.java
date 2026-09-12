package com.paycore.banksim;

/** The bank did not answer in time. */
public class BankTimeoutException extends Exception {

    public BankTimeoutException(String message) {
        super(message);
    }
}
