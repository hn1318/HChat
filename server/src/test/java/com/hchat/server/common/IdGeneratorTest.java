package com.hchat.server.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdGeneratorTest {

    @Test
    void generatesUniqueAscendingIds() {
        IdGenerator generator = new IdGenerator(1);
        Set<Long> seen = new HashSet<>();
        long previous = 0L;
        for (int i = 0; i < 20_000; i++) {
            long id = generator.nextId();
            assertThat(seen.add(id)).isTrue();
            assertThat(id).isGreaterThan(previous);
            previous = id;
        }
    }

    @Test
    void encodesWorkingTimestamp() {
        IdGenerator generator = new IdGenerator(7);
        long before = System.currentTimeMillis();
        long id = generator.nextId();
        long after = System.currentTimeMillis();
        long timestamp = IdGenerator.timestampOf(id);
        assertThat(timestamp).isBetween(before - 1, after + 1);
    }

    @Test
    void rejectsInvalidWorkerId() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new IdGenerator(9999))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
