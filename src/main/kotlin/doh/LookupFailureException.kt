package doh

/**
 * The DoH failed to complete the lookup successfully.
 */
public class LookupFailureException(message: String, cause: Throwable? = null) :
    DoHException(message, cause)