package com.nexusops.platform.cli;

import java.io.Console;

/**
 * System console for input; refuses to READ without an interactive terminal (secrets must not come from pipes).
 * Output goes to stdout, so usage and errors still print when no console is attached.
 */
final class ConsoleTerminal implements Terminal {

    private Console console() {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException(
                    "Run this command in an interactive terminal (for Docker: docker compose run -it ...).");
        }
        return console;
    }

    @Override
    public String readLine(String prompt) {
        return console().readLine("%s", prompt);
    }

    @Override
    public char[] readSecret(String prompt) {
        return console().readPassword("%s", prompt);
    }

    @Override
    public void println(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
