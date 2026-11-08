package dev.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** One versioned allowlist shared by dispatcher and collector; callers cannot override coordinates. */
public final class CityCatalog {
    private static final List<City> CITIES = load();
    private CityCatalog() {}
    public static List<City> all() { return CITIES; }
    public static Optional<City> find(Object id) {
        return CITIES.stream().filter(city -> city.id().equals(id)).findFirst();
    }
    private static List<City> load() {
        try (var input = CityCatalog.class.getResourceAsStream("/cities.json")) {
            var cities = List.of(new ObjectMapper().readValue(input, City[].class));
            if (cities.isEmpty() || cities.size() > 10 || cities.stream().map(City::id).distinct().count() != cities.size()
                    || cities.stream().anyMatch(c -> !c.id().matches("[a-z0-9-]{1,60}"))) {
                throw new IllegalArgumentException();
            }
            return cities;
        } catch (Exception e) { throw new IllegalStateException("Invalid cities.json configuration"); }
    }
}
