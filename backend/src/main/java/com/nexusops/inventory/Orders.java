package com.nexusops.inventory;

import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Rules shared by purchase and sales orders. Line field names follow the request: lines[i].name. */
final class Orders {

    static final int MAX_LINES = 100;
    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String FORBIDDEN = "You do not have permission to perform this action.";
    static final String ARCHIVED = "This record is archived.";
    static final String EMPTY_LINE = "Add a product and quantity.";
    static final String CHOOSE_LINE = "Choose a line of this order.";

    private Orders() {}

    static String field(int index, String name) {
        return item(index) + "." + name;
    }

    /** The field of a whole list item, for a {@code null} element. */
    static String item(int index) {
        return "lines[" + index + "]";
    }

    static void requireCount(List<?> lines) {
        if (lines == null || lines.isEmpty()) {
            throw ApiProblem.badRequestField("lines", "Add at least one line.");
        }
        if (lines.size() > MAX_LINES) {
            throw ApiProblem.badRequestField("lines", "Use at most " + MAX_LINES + " lines.");
        }
    }

    static void checkVersion(long current, Long version) {
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (current != version) {
            throw ApiProblem.conflict(STALE);
        }
    }

    static BigDecimal lineTotal(BigDecimal quantity, BigDecimal price) {
        return quantity.multiply(price).setScale(4, RoundingMode.HALF_UP);
    }
}
