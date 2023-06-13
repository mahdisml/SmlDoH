package doh


import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.http.content.ByteArrayContent
import okhttp3.OkHttpClient
import org.xbill.DNS.DClass
import org.xbill.DNS.InvalidTypeException
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import org.xbill.DNS.WireParseException
import java.io.Closeable
import java.io.IOException

/**
 * DNS-over-HTTPS (DoH) client.
 *
 * @param resolverURL The URL to the DoH resolver (defaults to Cloudflare)
 */
public class DoHClient(public val resolverURL: String = DEFAULT_RESOLVER_URL,proxyAddress:String? = null) : Closeable {
    private val ktorEngine = OkHttp.create {
        preconfigured = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    }
    internal var ktorClient = HttpClient(ktorEngine) {
        engine {
            proxyAddress?.let{
                proxy = ProxyBuilder.http(Url(it))
            }
        }
    }

    /**
     * Look up DNS record [name] of type [type].
     *
     * @param name The name to look up
     * @param type The type of record to look up (e.g., `AAAA`)
     * @throws [InvalidQueryException] if [name] or [type] are invalid
     * @throws [LookupFailureException] if the lookup was unsuccessful
     */
    @Throws(DoHException::class)
    public suspend fun lookUp(name: String, type: String): Answer {
        val querySerialised = makeQuery(name, type)
        val response: HttpResponse = try {
            ktorClient.post(resolverURL) {
                accept(DNS_MESSAGE_CONTENT_TYPE)
                setBody(ByteArrayContent(querySerialised, DNS_MESSAGE_CONTENT_TYPE))
            }
        } catch (exc: IOException) {
            throw LookupFailureException("Failed to connect to $resolverURL", exc)
        }
        if (response.status != HttpStatusCode.OK) {
            throw LookupFailureException("Unexpected HTTP response code (${response.status})")
        }
        return parseAnswer(response.body())
    }

    private fun makeQuery(name: String, type: String): ByteArray {
        val dnsName = Name(if (name.endsWith('.')) name else "$name.")
        val recordType = Type.value(type)
        val record = try {
            Record.newRecord(dnsName, recordType, DClass.IN)
        } catch (exc: InvalidTypeException) {
            throw InvalidQueryException("Invalid record type '$type'", exc)
        }
        val query = Message.newQuery(record)
        return query.toWire()
    }

    private fun parseAnswer(response: ByteArray): Answer {
        val responseMessage = try {
            Message(response)
        } catch (exc: WireParseException) {
            throw LookupFailureException("Returned DNS message is malformed", exc)
        }
        if (responseMessage.rcode != Rcode.NOERROR) {
            val rCodeString = Rcode.string(responseMessage.rcode)
            throw LookupFailureException("Lookup failed with $rCodeString error")
        }
        val records = responseMessage.getSection(Section.ANSWER)
        if (records.isEmpty()) {
            throw LookupFailureException("Answer data is empty")
        }
        return Answer(records.map { it.rdataToString() })
    }

    /**
     * Release the underlying resources used by the client.
     */
    override fun close() {
        ktorClient.close()
    }

    internal companion object {
        internal const val DEFAULT_RESOLVER_URL: String = "https://cloudflare-dns.com/dns-query"

        private val DNS_MESSAGE_CONTENT_TYPE = ContentType("application", "dns-message")
    }
}