package net.gaiadesk.e2e;

/** An operation, sealed: its request, and the seal its input and events go through. */
public final class SealedOperation {
    private final SealedRequest request;
    private final CallerSeal seal;

    SealedOperation(SealedRequest request, CallerSeal seal) {
        this.request = request;
        this.seal = seal;
    }

    /** The sealed request to send. */
    public SealedRequest getRequest() { return request; }

    /** The seal for its input and the desk's events. */
    public CallerSeal getSeal() { return seal; }
}
