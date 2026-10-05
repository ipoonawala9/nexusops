package com.nexusops.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PagingTest {

    @Test
    void defaultsAndBounds() {
        assertThat(Paging.of(null, null).getPageNumber()).isZero();
        assertThat(Paging.of(null, null).getPageSize()).isEqualTo(20);
        assertThat(Paging.of(3, 100).getPageSize()).isEqualTo(100);
    }

    @Test
    void rejectsOutOfRangeValuesAsFieldErrors() {
        assertThatThrownBy(() -> Paging.of(-1, 10)).isInstanceOfSatisfying(ApiProblem.class,
                p -> assertThat(p.errors().getFirst().field()).isEqualTo("page"));
        assertThatThrownBy(() -> Paging.of(0, 0)).isInstanceOfSatisfying(ApiProblem.class,
                p -> assertThat(p.errors().getFirst().field()).isEqualTo("size"));
        assertThatThrownBy(() -> Paging.of(0, 101)).isInstanceOf(ApiProblem.class);
    }
}
