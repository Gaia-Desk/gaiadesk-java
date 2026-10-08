package net.gaiadesk;

/** The shell a command runs in. {@link #POWERSHELL} is sent as {@code pwsh}, as gaiadesk-cli reads it. */
public enum Shell {
    /** The desk's own: {@code $SHELL -l -c} on macOS/Linux, {@code cmd.exe} on Windows (exec only). */
    DEFAULT("default"),
    /** No shell: the first argument is the program, the rest its arguments (exec only). */
    NONE("none"),
    /** {@code /bin/sh -c}. */
    SH("sh"),
    /** {@code bash -c}. */
    BASH("bash"),
    /** {@code zsh -c}. */
    ZSH("zsh"),
    /** {@code cmd /c}. */
    CMD("cmd"),
    /** PowerShell 7 ({@code pwsh}). */
    PWSH("pwsh"),
    /** The same as {@link #PWSH}. */
    POWERSHELL("pwsh");

    private final String wire;

    Shell(String wire) {
        this.wire = wire;
    }

    /** The name sent to the API. */
    public String wire() {
        return wire;
    }

    /** Can a background job use it? (A job is a command line: not {@code default} or {@code none}.) */
    public boolean forJobs() {
        return this != DEFAULT && this != NONE;
    }
}
