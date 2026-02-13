package io.atadflow.health;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.TimeUnit;

@Liveness
@ApplicationScoped
public class PythonLivenessCheck implements HealthCheck {

    @ConfigProperty(name = "python.executable")
    String pythonExecutable;

    @Override
    public HealthCheckResponse call() {
        try {
            Process process = new ProcessBuilder(pythonExecutable, "--version")
                .redirectErrorStream(true)
                .start();

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0) {
                return HealthCheckResponse.up("python");
            }
            return HealthCheckResponse.down("python");
        } catch (Exception e) {
            return HealthCheckResponse.down("python");
        }
    }
}
