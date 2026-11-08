package dev.weather;

public record City(String id, String name, String country, double latitude, double longitude) {
    public City {
        if (id == null || id.isBlank() || name == null || name.isBlank() || country == null || country.isBlank()
                || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Invalid city configuration");
        }
    }
}
