package dev.weather;

public record Reading(String cityId, String cityName, String country, double temperatureC,
                      int humidityPercent, double windSpeedMps, int conditionCode, String condition,
                      String observedAt, String collectedAt, String provider) {}
