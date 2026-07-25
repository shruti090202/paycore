package com.paycore.merchant;

import com.paycore.common.error.ErrorType;
import com.paycore.common.error.PayCoreException;
import com.paycore.common.id.Ids;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;

@Service
public class MerchantService {

    public static final int DEFAULT_FEE_BPS = 200;
    public static final long DEFAULT_FEE_FIXED_MINOR = 300;

    private final MerchantRepository merchants;
    private final JdbcAggregateTemplate template;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    /** A real bcrypt hash computed at startup; used only to equalize timing when the email is unknown. */
    private final String dummyHash;

    public MerchantService(MerchantRepository merchants, JdbcAggregateTemplate template,
                           PasswordEncoder passwordEncoder, Clock clock) {
        this.merchants = merchants;
        this.template = template;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public Merchant signup(String name, String email, String rawPassword) {
        return create(name, email, rawPassword, false);
    }

    @Transactional
    public Merchant create(String name, String email, String rawPassword, boolean demo) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        Merchant merchant = new Merchant(Ids.newId(Ids.MERCHANT), name.trim(), normalizedEmail,
                passwordEncoder.encode(rawPassword), DEFAULT_FEE_BPS, DEFAULT_FEE_FIXED_MINOR, demo, null,
                clock.instant());
        try {
            // insert(), not save(): we assign ids ourselves, and insert() makes "this must be new" explicit.
            return template.insert(merchant);
        } catch (DuplicateKeyException e) {
            // The unique index is the real guard; this just translates it into a friendly error.
            throw new PayCoreException(ErrorType.CONFLICT, "email_taken", "An account with this email already exists", "email");
        }
    }

    /**
     * Same error for "no such email" and "wrong password", and the password check runs even when the
     * email is unknown, so timing does not reveal which emails are registered.
     */
    @Transactional(readOnly = true)
    public Merchant authenticate(String email, String rawPassword) {
        Merchant merchant = merchants.findByEmail(email.trim().toLowerCase(Locale.ROOT)).orElse(null);
        String hash = merchant == null ? dummyHash : merchant.passwordHash();
        boolean ok = passwordEncoder.matches(rawPassword, hash);
        if (merchant == null || !ok) {
            throw PayCoreException.unauthenticated("Invalid email or password");
        }
        return merchant;
    }

    @Transactional(readOnly = true)
    public Merchant require(String id) {
        return merchants.findById(id).orElseThrow(() -> PayCoreException.notFound("merchant", id));
    }

    @Transactional(readOnly = true)
    public Merchant requireDemo() {
        return merchants.findFirstByIsDemoTrue()
                .orElseThrow(() -> new PayCoreException(ErrorType.NOT_FOUND, "demo_unavailable",
                        "Demo merchant is not configured on this deployment"));
    }

}
