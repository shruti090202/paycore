package com.paycore.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Cursor-paginated list. Pass {@code next_cursor} back as {@code ?cursor=} to get the following page. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageDto<T>(String object, List<T> data, boolean hasMore, String nextCursor) {

    public static <T> PageDto<T> of(List<T> data, String nextCursor) {
        return new PageDto<>("list", data, nextCursor != null, nextCursor);
    }
}
