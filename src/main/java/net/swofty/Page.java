package net.swofty;

import java.util.List;

public record Page<T>(List<T> content, int page, int totalPages, long totalElements) {}
