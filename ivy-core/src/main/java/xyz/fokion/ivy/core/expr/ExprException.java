package xyz.fokion.ivy.core.expr;

/** A syntax or evaluation error of an expression, with the position it refers to. */
public final class ExprException extends RuntimeException {

    private final String source;
    private final int position;

    public ExprException(String message, String source, int position) {
        super(message);
        this.source = source;
        this.position = position;
    }

    public ExprException(String message) {
        this(message, null, -1);
    }

    /** The expression, when known. */
    public String source() {
        return source;
    }

    /** The offset in {@link #source()}, or -1. */
    public int position() {
        return position;
    }

    @Override
    public String getMessage() {
        String m = super.getMessage();
        if (source == null || position < 0) {
            return m;
        }
        return m + " at position " + (position + 1) + " in `" + source + "`";
    }
}
