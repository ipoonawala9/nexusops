package com.nexusops.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockKeyTest {

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID W1 = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID W2 = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void ordersByProductThenWarehouse() {
        List<StockKey> keys = new ArrayList<>(List.of(new StockKey(P2, W1), new StockKey(P1, W2),
                new StockKey(P2, W2), new StockKey(P1, W1)));
        Collections.sort(keys);
        assertThat(keys).containsExactly(new StockKey(P1, W1), new StockKey(P1, W2), new StockKey(P2, W1),
                new StockKey(P2, W2));
        assertThat(new StockKey(P1, W1).compareTo(new StockKey(P1, W1))).isZero();
    }
}
