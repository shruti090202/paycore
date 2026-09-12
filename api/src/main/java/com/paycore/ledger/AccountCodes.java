package com.paycore.ledger;

/** Chart of accounts. */
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
