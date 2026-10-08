package net.gaiadesk;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Agent token scopes (what a token minted with {@link GaiaDesk#createToken} may do on the desk). */
public final class Scopes {
    /** Run commands. */
    public static final String EXEC = "exec";
    /** Open shells. */
    public static final String SHELL = "shell";
    /** Copy files. */
    public static final String CP = "cp";
    /** Forward ports. */
    public static final String FORWARD = "forward";
    /** Background jobs. */
    public static final String JOBS = "jobs";
    /** The screen (Agent Access). */
    public static final String SCREEN = "screen";
    /**
     * Ask to run as administrator (root / SYSTEM, {@link ExecOptions#admin(boolean)}). Never implied: name it.
     * The desk owner's Admin access switch, turned on at the desk, still decides; a confined token
     * ({@code cwd}, {@code lowPriv}) cannot have it.
     */
    public static final String ADMIN = "admin";

    /** The default when none are given: exec, cp, jobs. */
    public static final List<String> DEFAULT = Collections.unmodifiableList(Arrays.asList(EXEC, CP, JOBS));

    private Scopes() {}
}
