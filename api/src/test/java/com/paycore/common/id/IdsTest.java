package com.paycore.common.id;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTest {

    @Test
    void idsCarryPrefixAndAreLowercaseUlids() {
        String id = Ids.newId(Ids.PAYMENT);
        assertThat(id).startsWith("pay_").hasSize(4 + 26).matches("pay_[0-9a-z]{26}");
        assertThat(Ids.hasPrefix(id, Ids.PAYMENT)).isTrue();
        assertThat(Ids.hasPrefix(id, Ids.REFUND)).isFalse();
    }

    @Test
    void idsGeneratedInSequenceSortLexicographically() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(Ids.newId(Ids.EVENT));
        }
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);
        // Monotonic ULIDs: generation order == sort order, which is what cursor pagination relies on.
        assertThat(ids).containsExactlyElementsOf(sorted);
        assertThat(ids).doesNotHaveDuplicates();
    }
}
