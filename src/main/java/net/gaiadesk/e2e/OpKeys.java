package net.gaiadesk.e2e;

/** One operation's three keys: {@code request}, {@code input} and {@code event}. */
public final class OpKeys {
    final byte[] request;
    final byte[] input;
    final byte[] event;

    OpKeys(byte[] request, byte[] input, byte[] event) {
        this.request = request;
        this.input = input;
        this.event = event;
    }

    /** The key of the request. */
    public byte[] request() { return request.clone(); }

    /** The key of the input frames. */
    public byte[] input() { return input.clone(); }

    /** The key of the desk's events. */
    public byte[] event() { return event.clone(); }
}
