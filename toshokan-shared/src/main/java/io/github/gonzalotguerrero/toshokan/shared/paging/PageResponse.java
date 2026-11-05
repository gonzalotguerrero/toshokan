package io.github.gonzalotguerrero.toshokan.shared.paging;

import java.util.List;

/**
 * A page of results
 */
public record PageResponse<T>(List<T> items, String nextPageToken) {

    public PageResponse {
        items = List.copyOf(items);
    }
}
