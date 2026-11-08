package dev.weather.infra;

import java.nio.file.Files;
import java.nio.file.Path;
import software.amazon.awscdk.*;

public final class WeatherApp {
    public static final String ACCOUNT = "471084227270";
    public static final String REGION = "us-east-2";
    public static void main(String[] args) {
        var app = new App();
        new WeatherStack(app, "WeatherDev", StackProps.builder()
                .env(Environment.builder().account(ACCOUNT).region(REGION).build()).build(), artifact());
        app.synth();
    }
    public static String artifact() {
        for (var file : new String[]{"target/weather-lambda.zip", "../target/weather-lambda.zip"})
            if (Files.isRegularFile(Path.of(file))) return Path.of(file).toAbsolutePath().normalize().toString();
        throw new IllegalStateException("Build the backend first with mvn package");
    }
}
