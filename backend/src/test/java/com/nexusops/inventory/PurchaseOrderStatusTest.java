package com.nexusops.inventory;

import static com.nexusops.inventory.PurchaseOrderStatus.CANCELLED;
import static com.nexusops.inventory.PurchaseOrderStatus.DRAFT;
import static com.nexusops.inventory.PurchaseOrderStatus.ORDERED;
import static com.nexusops.inventory.PurchaseOrderStatus.PARTIALLY_RECEIVED;
import static com.nexusops.inventory.PurchaseOrderStatus.RECEIVED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PurchaseOrderStatusTest {

    @Test
    void onlyOrderedOrdersReceive() {
        assertThat(Arrays.stream(PurchaseOrderStatus.values()).filter(PurchaseOrderStatus::receivable))
                .containsExactly(ORDERED, PARTIALLY_RECEIVED);
    }

    @Test
    void everythingButAReceivedOrCancelledOrderCancels() {
        assertThat(Arrays.stream(PurchaseOrderStatus.values()).filter(PurchaseOrderStatus::cancellable))
                .containsExactly(DRAFT, ORDERED, PARTIALLY_RECEIVED);
        assertThat(RECEIVED.cancellable()).isFalse();
        assertThat(CANCELLED.cancellable()).isFalse();
    }
}
