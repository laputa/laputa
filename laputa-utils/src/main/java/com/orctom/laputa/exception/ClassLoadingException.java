package com.orctom.laputa.exception;

public class ClassLoadingException extends RuntimeException {

  public ClassLoadingException(String message) {
    super(message);
  }

  public ClassLoadingException(String message, Throwable cause) {
    super(message, cause);
  }

  public ClassLoadingException(Throwable cause) {
    super(cause);
  }

  protected ClassLoadingException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }
}
