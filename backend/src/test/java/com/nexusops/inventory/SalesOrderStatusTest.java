package com.nexusops.inventory;

import static com.nexusops.inventory.SalesOrderStatus.CONFIRMED;
import static com.nexusops.inventory.SalesOrderStatus.DRAFT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SalesOrderStatusTest {

    @Test
    void onlyOpenOrdersCancel() {
        assertThat(Arrays.stream(SalesOrderStatus.values()).filter(SalesOrderStatus::cancellable))
                .containsExactly(DRAFT, CONFIRMED);
    }
}
