package net.gaiadesk.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.gaiadesk.ErrorDetails;
import net.gaiadesk.UsageException;

/** The argument checks gaiadesk-cli makes, made before anything is sent (the same UsageExceptions as the CLI). */
public final class Check {
    private static final Pattern JOB_NAME = Pattern.compile("[A-Za-z0-9._][A-Za-z0-9._-]*");
    private static final Pattern DURATION = Pattern.compile("\\s*\\d+\\s*[a-z]*(\\s*\\d+\\s*[a-z]+)*\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([a-z]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEM = Pattern.compile("\\s*(\\d+)\\s*([MG])?B?\\s*", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Long> UNITS = new LinkedHashMap<>();

    static {
        for (String u : new String[] {"s", "sec", "secs"}) UNITS.put(u, 1L);
        for (String u : new String[] {"m", "min", "mins"}) UNITS.put(u, 60L);
        UNITS.put("h", 3600L);
        UNITS.put("d", 86400L);
        UNITS.put("w", 604800L);
    }

    private Check() {}

    public static UsageException usage(String message) {
        return new UsageException(message, ErrorDetails.builder().kind("usage").build());
    }

    public static UsageException usage(String message, String op) {
        return new UsageException(message, ErrorDetails.builder().kind("usage").argv(List.of(op)).build());
    }

    /** A desk id: one token, no whitespace, not a flag. */
    public static String desk(String deskId) {
        if (deskId == null || deskId.trim().isEmpty()) throw usage("a desk id is required");
        String d = deskId.trim();
        if (d.chars().anyMatch(Character::isWhitespace) || d.startsWith("-")) throw usage("not a desk id: " + quote(deskId));
        return d;
    }

    /** A job name: letters, digits, . _ - (not starting with -). */
    public static String jobName(String name) {
        if (name == null || !JOB_NAME.matcher(name).matches()) {
            throw usage("a job name is letters, digits, . _ - (not starting with -): " + quote(name));
        }
        return name;
    }

    /** A directory on the desk. */
    public static String cwd(String cwd) {
        if (cwd == null || cwd.trim().isEmpty() || cwd.indexOf('\0') >= 0) throw usage("cwd is a directory on the desk: " + quote(cwd));
        return cwd;
    }

    /** Environment variables: names non-empty, without {@code =}, whitespace or NUL; values without NUL. Errors name the variable, never its value. */
    public static Map<String, String> env(Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || k.isEmpty() || k.chars().anyMatch(c -> c == '=' || c == 0 || Character.isWhitespace(c))) {
                throw usage("env: " + quote(k) + " is not an environment variable name");
            }
            if (v == null) throw usage("env: the value of " + k + " must be a string");
            if (v.indexOf('\0') >= 0) throw usage("env: the value of " + k + " contains a NUL byte");
            out.put(k, v);
        }
        return out;
    }

    /** A duration as whole seconds: {@code 90}, {@code 30s}, {@code 10m}, {@code 1h30m}, {@code 7d}, {@code 2w}. */
    public static long seconds(String v, String what) {
        if (v == null || !DURATION.matcher(v).matches()) throw usage(what + ": not a duration: " + quote(v));
        String d = v.trim();
        if (d.matches("\\d+")) return Long.parseLong(d);
        long total = 0;
        Matcher m = PART.matcher(d);
        while (m.find()) {
            Long unit = UNITS.get(m.group(2).toLowerCase(Locale.ROOT));
            if (unit == null) throw usage(what + ": unknown unit in " + quote(v));
            total += Long.parseLong(m.group(1)) * unit;
        }
        return total;
    }

    /** Seconds as a number: whole seconds, rounded up. */
    public static long seconds(double v, String what) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) throw usage(what + " must be a number of seconds >= 0");
        return (long) Math.ceil(v);
    }

    /** Megabytes: a number, or {@code 512M}, {@code 2G}. */
    public static long memMb(String mem) {
        Matcher m = MEM.matcher(mem == null ? "" : mem);
        if (!m.matches()) throw usage("mem: not a size: " + quote(mem));
        long n = Long.parseLong(m.group(1));
        return m.group(2) != null && m.group(2).equalsIgnoreCase("G") ? n * 1024 : n;
    }

    public static String quote(Object s) {
        return s == null ? "null" : Json.write(String.valueOf(s));
    }
}
