package doh

public abstract class DoHException(message: String, cause: Throwable?) : Exception(message, cause)