package communicationmod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Reads stderr from the subprocess and logs each line to the game's logger.
 * This allows pytest output and other subprocess messages to appear in the game logs.
 */
public class ErrorLogger implements Runnable {

    private final InputStream stream;
    private static final Logger logger = LogManager.getLogger(ErrorLogger.class.getName());

    public ErrorLogger(InputStream stream) {
        this.stream = stream;
    }

    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                logger.info("[subprocess] " + line);
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                logger.error("Error reading from subprocess stderr", e);
            }
        }
        logger.info("Subprocess stderr reader thread finished.");
    }
}
