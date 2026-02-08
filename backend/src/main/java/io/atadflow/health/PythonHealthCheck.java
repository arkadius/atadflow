package io.atadflow.health;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
@Startup
@UnlessBuildProfile("test")
public class PythonHealthCheck {

    @ConfigProperty(name = "python.executable")
    String pythonExecutable;

    PythonHealthCheck() {
    }

    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        checkPython();
        checkPySpark();
    }

    private void checkPython() {
        String output = runCommand(pythonExecutable, "--version");
        if (output == null) {
            throw new RuntimeException("Python is not available at: " + pythonExecutable);
        }
        Log.infof("Python check: %s (executable: %s)", output.strip(), pythonExecutable);
    }

    private void checkPySpark() {
        String output = runCommand(pythonExecutable, "-c", "import pyspark; print(pyspark.__version__)");
        if (output == null) {
            throw new RuntimeException("PySpark is not available. Install PySpark (pip install pyspark) in: " + pythonExecutable);
        }
        Log.infof("PySpark check: %s", output.strip());
    }

    private String runCommand(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                return reader.readLine();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
