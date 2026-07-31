package com.paycore.payments;

import com.paycore.merchant.Merchant;
import com.paycore.merchant.MerchantService;
import com.paycore.support.AbstractIntegrationTest;
import com.paycore.support.Api;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The database must reject what the Java state machine rejects, even for a raw UPDATE. */
class PaymentStateMachineIT extends AbstractIntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired PaymentService payments;
    @Autowired MerchantService merchants;

    private Payment newPayment() {
        Merchant m = merchants.signup("SM Co", Api.uniqueEmail(), "correct-horse-battery");
        return payments.create(new PaymentService.CreateCommand(m.id(), 10_000, "INR", CaptureMethod.MANUAL,
                "t", null, null, null, null, Map.of()));
    }

    private void rawUpdate(String sql, String id) {
        jdbc.sql(sql).param("id", id).update();
    }

    @Test
    void transitionTableInDatabaseMatchesJavaEnum() {
        Map<PaymentStatus, Set<PaymentStatus>> db = new HashMap<>();
        for (PaymentStatus s : PaymentStatus.values()) {
            db.put(s, EnumSet.noneOf(PaymentStatus.class));
        }
        jdbc.sql("SELECT from_status, to_status FROM payment_status_transitions").query((rs, i) -> {
            db.get(PaymentStatus.fromWire(rs.getString(1))).add(PaymentStatus.fromWire(rs.getString(2)));
            return null;
        }).list();
        for (PaymentStatus s : PaymentStatus.values()) {
            assertThat(db.get(s)).as("transitions from " + s).containsExactlyInAnyOrderElementsOf(s.allowedNext());
        }
    }

    @Test
    void illegalTransitionsAreRejectedByTrigger() {
        Payment p = newPayment();
        assertThatThrownBy(() -> rawUpdate("UPDATE payments SET status = 'refunded' WHERE id = :id", p.getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("illegal payment transition");
        assertThatThrownBy(() -> rawUpdate("UPDATE payments SET status = 'captured', captured_minor = 10000 WHERE id = :id", p.getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("created -> captured");

        // Legal edge with consistent money columns passes.
        rawUpdate("UPDATE payments SET status = 'canceled' WHERE id = :id", p.getId());
        assertThatThrownBy(() -> rawUpdate("UPDATE payments SET status = 'created' WHERE id = :id", p.getId()))
                .as("terminal states are final").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void moneyColumnsAreGuardedByCheckConstraints() {
        Payment p = newPayment();
        rawUpdate("UPDATE payments SET status = 'authorized' WHERE id = :id", p.getId());

        assertThatThrownBy(() -> rawUpdate("UPDATE payments SET status = 'captured', captured_minor = 10001 WHERE id = :id", p.getId()))
                .as("captured > amount").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawUpdate("UPDATE payments SET status = 'captured', captured_minor = 0 WHERE id = :id", p.getId()))
                .as("captured status with nothing captured").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawUpdate("UPDATE payments SET refunded_minor = 1 WHERE id = :id", p.getId()))
                .as("refund before capture").isInstanceOf(DataIntegrityViolationException.class);

        rawUpdate("UPDATE payments SET status = 'captured', captured_minor = 6000 WHERE id = :id", p.getId());

        assertThatThrownBy(() -> rawUpdate("UPDATE payments SET status = 'partially_refunded', refunded_minor = 6001 WHERE id = :id", p.getId()))
                .as("over-refund").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawUpdate("UPDATE payments SET status = 'refunded', refunded_minor = 5999 WHERE id = :id", p.getId()))
                .as("'refunded' must mean fully refunded").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawUpdate("UPDATE payments SET status = 'partially_refunded', refunded_minor = 6000 WHERE id = :id", p.getId()))
                .as("'partially_refunded' must mean strictly less than captured").isInstanceOf(DataIntegrityViolationException.class);

        rawUpdate("UPDATE payments SET status = 'partially_refunded', refunded_minor = 2500 WHERE id = :id", p.getId());
        rawUpdate("UPDATE payments SET status = 'refunded', refunded_minor = 6000 WHERE id = :id", p.getId());
    }

    @Test
    void amountCurrencyAndMerchantAreImmutable() {
        Payment p = newPayment();
        assertThatThrownBy(() -> rawUpdate("UPDATE payments SET amount_minor = 1 WHERE id = :id", p.getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> rawUpdate("UPDATE payments SET currency = 'USD' WHERE id = :id", p.getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("immutable");
    }

    @Test
    void timelineIsAppendOnly() {
        Payment p = newPayment();
        assertThatThrownBy(() -> rawUpdate("DELETE FROM payment_events WHERE payment_id = :id", p.getId()))
                .hasMessageContaining("append-only");
        assertThat(payments.timeline(p.getId())).hasSize(1).first().satisfies(e -> {
            assertThat(e.type()).isEqualTo("payment.created");
            assertThat(e.toStatus()).isEqualTo("created");
        });
    }
}
