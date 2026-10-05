package com.nexusops.platform.cli;

/** The CLI's only I/O. Secrets are read without echo and are never written to a logger. */
public interface Terminal {

    String readLine(String prompt);

    char[] readSecret(String prompt);

    void println(String line);
}
