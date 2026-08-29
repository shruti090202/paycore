package com.paycore.reconciliation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconcilerParseTest {

    @Test
    void parsesTheSimulatorFormat() {
        String csv = """
                bank_ref,kind,parent_ref,amount_minor,currency,card_last4,processed_at
                bref_1,authorize,,10000,INR,4242,2026-09-12T00:00:00Z
                bref_2,refund,bref_1,2500,INR,4242,2026-09-12T00:01:00Z

                """;
        List<Reconciler.CsvRow> rows = Reconciler.parse(csv);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).isEqualTo(new Reconciler.CsvRow("bref_1", "authorize", null, 10000, "INR", 2));
        assertThat(rows.get(1).parentRef()).isEqualTo("bref_1");
        assertThat(rows.get(1).line()).isEqualTo(3);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> Reconciler.parse("nope")).hasMessageContaining("no header");
        assertThatThrownBy(() -> Reconciler.parse("bank_ref,kind,parent_ref,amount_minor,currency,card_last4,processed_at\nx,authorize,,abc,INR,,t"))
                .hasMessageContaining("line 2");
    }
}
