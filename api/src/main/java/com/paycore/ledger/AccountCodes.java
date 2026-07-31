package com.paycore.ledger;

/**
 * Chart of accounts. Codes are currency-suffixed because a ledger account holds exactly one currency.
 * <pre>
 *  bank_receivable:INR          asset      what the acquiring bank owes us for captured card payments
 *  settlement_cash:INR          asset      money the bank has actually settled to our account
 *  fee_revenue:INR              revenue    our gateway fees
 *  merchant_payable:mer_x:INR   liability  what we owe merchant x (their "balance")
 * </pre>
 */
public final class AccountCodes {

    private AccountCodes() {
    }

    public static String bankReceivable(String currency) {
        return "bank_receivable:" + currency;
    }

    public static String settlementCash(String currency) {
        return "settlement_cash:" + currency;
    }

    public static String feeRevenue(String currency) {
        return "fee_revenue:" + currency;
    }

    public static String merchantPayable(String merchantId, String currency) {
        return "merchant_payable:" + merchantId + ":" + currency;
    }
}
