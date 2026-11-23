package com.helpdesk.common;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * A stable JSON shape for paged results (Spring's {@code Page} serialises its internals,
 * which change between versions).
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
