package dev.agentops.shared;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int page, int size) {
    public static int offset(int page, int size) {
        if (page < 0 || page > 100_000 || size < 1 || size > 100) throw ApiError.badRequest("INVALID_PAGINATION");
        return Math.multiplyExact(page, size);
    }
}
