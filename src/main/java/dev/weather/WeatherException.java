package dev.weather;

public final class WeatherException extends RuntimeException {
    public enum Category { AUTHENTICATION, THROTTLED, PROVIDER, INVALID_RESPONSE, NETWORK }
    private final Category category;
    public WeatherException(Category category) {
        super("Weather request failed: " + category);
        this.category = category;
    }
    public Category category() { return category; }
}
