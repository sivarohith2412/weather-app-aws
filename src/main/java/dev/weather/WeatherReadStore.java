package dev.weather;

import java.time.Instant;
import java.util.List;

public interface WeatherReadStore {
    record Page(List<Reading> readings, boolean truncated) {}
    Page query(String cityId, Instant from, Instant to, int limit);
}
