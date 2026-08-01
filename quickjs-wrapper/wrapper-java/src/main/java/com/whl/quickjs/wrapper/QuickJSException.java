package com.whl.quickjs.wrapper;

/**
 * Created by Harlon Wang on 2022/2/8.
 */
public class QuickJSException extends RuntimeException {

    private final boolean jsError;

    public QuickJSException(String message) {
        this(message, false);
    }

    public QuickJSException(String message, boolean jsError) {
        super(message);
        this.jsError = jsError;
    }

    public QuickJSException(String message, Throwable cause) {
        super(message, cause);
        this.jsError = false;
    }

    public boolean isJSError() {
        return jsError;
    }
}
