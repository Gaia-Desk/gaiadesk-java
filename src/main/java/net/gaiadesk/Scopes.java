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

    /** The default when none are given: exec, cp, jobs. */
    public static final List<String> DEFAULT = Collections.unmodifiableList(Arrays.asList(EXEC, CP, JOBS));

    private Scopes() {}
}
