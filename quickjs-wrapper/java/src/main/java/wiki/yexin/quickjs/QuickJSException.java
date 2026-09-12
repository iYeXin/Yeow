package wiki.yexin.quickjs;

/** Thrown when the QuickJS engine reports an error, or the bridge fails to load. */
public class QuickJSException extends RuntimeException {

    public QuickJSException(String message) {
        super(message);
    }

    public QuickJSException(String message, Throwable cause) {
        super(message, cause);
    }
}
