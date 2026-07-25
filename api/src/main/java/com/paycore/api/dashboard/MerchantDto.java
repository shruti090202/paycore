package com.paycore.api.dashboard;

import com.paycore.merchant.Merchant;

import java.time.Instant;

/** Public shape of a merchant; the password hash never leaves the service layer. */
public record MerchantDto(String id, String name, String email, int feeBps, long feeFixedMinor, boolean isDemo,
                          Instant createdAt) {

    public static MerchantDto from(Merchant m) {
        return new MerchantDto(m.id(), m.name(), m.email(), m.feeBps(), m.feeFixedMinor(), m.isDemo(), m.createdAt());
    }
}
