package dev.weather;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import java.lang.reflect.Proxy;
final class TestFixtures {
    static final Reading READING = new Reading("us-boston", "Boston", "US", 12.4, 65, 3.1, 803,
            "clouds", "2026-11-07T14:00:00Z", "2026-11-07T14:02:00Z", "openweather");
    static Context context() {
        return (Context) Proxy.newProxyInstance(Context.class.getClassLoader(), new Class<?>[]{Context.class}, (p,m,a) -> {
            return switch (m.getName()) {
                case "getMemoryLimitInMB" -> 512;
                case "getRemainingTimeInMillis" -> 180000;
                case "getAwsRequestId" -> "test-request-id";
                case "getFunctionName" -> "weather-app-dev";
                case "getFunctionVersion" -> "$LATEST";
                case "getInvokedFunctionArn" -> "arn:aws:lambda:us-east-2:471084227270:function:weather-app-dev";
                case "getLogger" -> new LambdaLogger() {
                    public void log(String message) {}
                    public void log(byte[] message) {}
                };
                default -> "test";
            };
        });
    }
}
