package org.onekash.kashcal.network

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import org.onekash.kashcal.util.maskHost
import java.io.IOException

/** Why the transport guard stopped a request instead of sending it. */
enum class DavTransportRefusal {
    /** An https request was redirected to plain http on another host or port. */
    INSECURE_REDIRECT,

    /** A redirect to another origin (scheme, host or port), which the request — and with
     * it the account's method, body and headers — must never cross. The same host
     * upgrading http to https is not one of those. */
    CROSS_ORIGIN_REDIRECT,

    /** A plain http request on an account set up with https. */
    CLEARTEXT_REFUSED,

    /** More than [DavRedirectPolicy.MAX_REDIRECTS] redirects in a row (a loop). */
    TOO_MANY_REDIRECTS,

    /** A redirect of a request whose body can only be written once. */
    UNREPLAYABLE_BODY,
}

/**
 * The guard refused to send a request. Carries masked hosts only, never a path,
 * query or credentials, so the message is safe for logs and the sync history.
 */
class DavTransportRefusedException(
    val reason: DavTransportRefusal,
    fromHost: String,
    toHost: String?,
) : IOException(
    "Connection refused by KashCal ($reason): ${fromHost.maskHost()}" +
        (toHost?.let { " -> ${it.maskHost()}" } ?: "")
)

/**
 * Where a DAV request goes after a redirect, decided without touching the network.
 *
 * - 301, 302, 307 and 308 are followed for every method with the same method and
 *   body (a WebDAV method has no GET equivalent, so turning a PUT or DELETE into a
 *   GET would silently drop the write).
 * - 303 is followed only for GET, HEAD and PROPFIND: RFC 6764 §5 names 303 for
 *   the well-known URI, while for a write a 303 means "done, look elsewhere".
 * - https to http: re-sent as https when the target is the same host on port 80 or
 *   on the port that answered over https (a TLS-terminating proxy that writes http
 *   into its Location header). Any other downgrade is refused.
 * - A plain http target is refused unless the account itself was set up with http.
 * - A redirect that leaves the origin the request belongs to — another scheme, host or
 *   port — is refused: the re-sent request carries the account's method, body and headers,
 *   and browsers likewise never let a credentialed request cross origins on a redirect.
 *   The one exception is the same host upgrading http to https, followed whatever port
 *   the https side uses (Baikal's well-known hands that out). [isAllowedOrigin] decides
 *   where the request may still go: the account's own origin plus the hosts its provider
 *   calls the same server (iCloud's numbered partitions); it defaults to the origin the
 *   request started on.
 */
object DavRedirectPolicy {
    const val MAX_REDIRECTS = 5

    private val ALWAYS_FOLLOWED = setOf(301, 302, 307, 308)
    private val SEE_OTHER_METHODS = setOf("GET", "HEAD", "PROPFIND")

    /** Whether [a] and [b] share an origin: scheme, host and port (RFC 6454). */
    fun isSameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.port == b.port && a.host.equals(b.host, ignoreCase = true)

    sealed interface Decision {
        /** Not a redirect to follow: hand the response to the caller as it is. */
        data object Stop : Decision
        data class Follow(val url: HttpUrl) : Decision
        data class Refuse(val reason: DavTransportRefusal, val target: HttpUrl) : Decision
    }

    fun next(
        from: HttpUrl,
        code: Int,
        location: String?,
        method: String,
        allowCleartext: Boolean,
        isAllowedOrigin: (HttpUrl) -> Boolean = { DavRedirectPolicy.isSameOrigin(it, from) },
    ): Decision {
        val followed = code in ALWAYS_FOLLOWED || (code == 303 && method.uppercase() in SEE_OTHER_METHODS)
        if (!followed || location.isNullOrBlank()) return Decision.Stop
        val target = from.resolve(location) ?: return Decision.Stop

        val next = if (from.isHttps && !target.isHttps) {
            val sameHost = target.host.equals(from.host, ignoreCase = true)
            if (sameHost && (target.port == 80 || target.port == from.port)) {
                target.newBuilder().scheme("https").port(from.port).build()
            } else {
                return Decision.Refuse(DavTransportRefusal.INSECURE_REDIRECT, target)
            }
        } else {
            target
        }
        if (!isAllowedOrigin(next)) {
            // The one crossing allowed: the same host moving the call from http to https,
            // whatever port the https side uses. The account's own server over TLS is not
            // a foreign host, and a plain-http account's origin rule admits it too.
            val sameHostTlsUpgrade = !from.isHttps && next.isHttps &&
                next.host.equals(from.host, ignoreCase = true)
            if (!sameHostTlsUpgrade) {
                return Decision.Refuse(DavTransportRefusal.CROSS_ORIGIN_REDIRECT, next)
            }
        }
        return Decision.Follow(next)
    }
}

/**
 * Follows redirects for DAV requests itself, as an OkHttp application interceptor.
 *
 * OkHttp's own follow-up turns a redirected PUT, DELETE, REPORT or MOVE into a GET,
 * which a server usually answers 200: the write never happens yet reads as a
 * success. This re-sends the same method, body and headers (If-Match, Depth,
 * Destination, ...) to the new location, up to [DavRedirectPolicy.MAX_REDIRECTS]
 * http:// address ([allowCleartext]), and never follows a redirect outside the origin
 * the request belongs to ([isAccountOrigin], or the URL the call started on when the
 * client was built without an account) — except the same host upgrading http to https.
 *
 * The returned response's `request.url` is where the request ended up, and its
 * `priorResponse` chain holds each redirect (bodies dropped), like OkHttp's own.
 * Install it with [installDavTransport], which also turns OkHttp's redirects off.
 */
class DavTransportGuard(
    private val allowCleartext: Boolean,
    /**
     * Which URLs still belong to the account this client was built for: its origin
     * (scheme, host, port) and the hosts its provider calls the same server. Cleartext
     * and redirects stay inside it. Null anchors the origin at the URL the call starts
     * on, for clients built without an account (empty-credential probes).
     */
    private val isAccountOrigin: ((HttpUrl) -> Boolean)? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val startUrl = request.url
        val origin = isAccountOrigin ?: { url: HttpUrl -> DavRedirectPolicy.isSameOrigin(url, startUrl) }
        if (!request.url.isHttps && !(allowCleartext && origin(request.url))) {
            throw refuse(DavTransportRefusal.CLEARTEXT_REFUSED, request.url, null)
        }

        var response = chain.proceed(request)
        var prior: Response? = null
        var redirects = 0
        while (true) {
            val decision = DavRedirectPolicy.next(
                from = request.url,
                code = response.code,
                location = response.header("Location"),
                method = request.method,
                allowCleartext = allowCleartext,
                isAllowedOrigin = origin,
            )
            val target = when (decision) {
                DavRedirectPolicy.Decision.Stop ->
                    return if (prior == null) response else response.newBuilder().priorResponse(prior).build()
                is DavRedirectPolicy.Decision.Refuse -> {
                    response.close()
                    throw refuse(decision.reason, request.url, decision.target)
                }
                is DavRedirectPolicy.Decision.Follow -> decision.url
            }
            if (redirects == DavRedirectPolicy.MAX_REDIRECTS) {
                response.close()
                throw refuse(DavTransportRefusal.TOO_MANY_REDIRECTS, request.url, target)
            }
            if (request.body?.isOneShot() == true) {
                response.close()
                throw refuse(DavTransportRefusal.UNREPLAYABLE_BODY, request.url, target)
            }

            prior = response.newBuilder().body(ResponseBody.EMPTY).priorResponse(prior).build()
            response.close()
            redirects++
            request = request.newBuilder().url(target).build()
            response = chain.proceed(request)
        }
    }

    private fun refuse(reason: DavTransportRefusal, from: HttpUrl, to: HttpUrl?): DavTransportRefusedException {
        val e = DavTransportRefusedException(reason, from.host, to?.host)
        Log.w(TAG, e.message.orEmpty())
        return e
    }

    companion object {
        private const val TAG = "DavTransportGuard"

        /** Returns whether an account whose [accountUrl] starts with http:// may use plain http. */
        fun allowsCleartext(accountUrl: String?): Boolean =
            accountUrl?.trimStart()?.startsWith("http://", ignoreCase = true) == true

        /**
         * The origin rule for an account set up on [accountUrl]: a URL belongs to the
         * account when its host is the account's own (or one [isSameServerHost] calls the
         * same server, e.g. iCloud's numbered partitions) and it shares the account's
         * scheme and port — or, for a plain-http account, it is that same host over https,
         * the upgrade a redirecting server (Baikal's well-known) hands out. An account URL
         * that doesn't parse yields a rule nothing satisfies, so credentials and cleartext
         * fail closed.
         */
        fun accountOriginRule(
            accountUrl: String?,
            isSameServerHost: (accountHost: String, host: String) -> Boolean =
                { accountHost, host -> accountHost.equals(host, ignoreCase = true) },
        ): (HttpUrl) -> Boolean {
            val base = accountUrl?.toHttpUrlOrNull() ?: return { false }
            return { url ->
                val ownHost = url.host.equals(base.host, ignoreCase = true) || isSameServerHost(base.host, url.host)
                val sameOrigin = url.scheme == base.scheme && url.port == base.port
                val sameHostTlsUpgrade = !base.isHttps && url.isHttps
                ownHost && (sameOrigin || sameHostTlsUpgrade)
            }
        }

        /**
         * Returns whether the account's credentials may go on a request to [url]: only
         * within the account's origin ([isAccountOrigin]; a caller that passes no rule
         * keeps the old scheme-only check), and never over plain http unless the account
         * itself is http ([allowCleartext]).
         */
        fun mayAttachCredentials(
            url: HttpUrl,
            allowCleartext: Boolean,
            isAccountOrigin: (HttpUrl) -> Boolean = { it.isHttps || allowCleartext },
        ): Boolean = isAccountOrigin(url) && (url.isHttps || allowCleartext)
    }
}

/**
 * Turns OkHttp's own redirect handling off and follows redirects with [DavTransportGuard].
 * Call it on the builder that carries the account's auth. [isAccountOrigin] scopes where
 * the account's requests, credentials and redirects may go; omit it for a client that has
 * no account of its own.
 */
fun OkHttpClient.Builder.installDavTransport(
    allowCleartext: Boolean,
    isAccountOrigin: ((HttpUrl) -> Boolean)? = null,
): OkHttpClient.Builder =
    followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(DavTransportGuard(allowCleartext, isAccountOrigin))

/**
 * Returns a copy of this client that follows no redirects, neither through
 * [DavTransportGuard] nor through OkHttp's own (for a contact photo, whose redirect may
 * point at a foreign host).
 */
fun OkHttpClient.withoutDavTransportGuard(): OkHttpClient =
    newBuilder()
        .apply { interceptors().removeAll { it is DavTransportGuard } }
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

/**
 * Returns the DAV endpoint a well-known discovery ended at (RFC 6764 §5), cleaned for
 * storage: query and fragment dropped, the path kept as is (it is the service's
 * context path; trailing slashes matter to some servers).
 *
 * The scheme never goes down: an https result stays https even if the user typed
 * http (the server upgraded), and an http result is lifted back to https when the
 * user started on https (a TLS-terminating proxy that writes http:// into its
 * redirects). So a stored https address is never rewritten to plain http.
 */
fun wellKnownEndpoint(finalUrl: String, originalScheme: String?): String {
    val cleanUrl = finalUrl.substringBefore("?").substringBefore("#")
    val scheme = cleanUrl.substringBefore("://", missingDelimiterValue = "")
    if (scheme.isEmpty() || !scheme.equals("http", ignoreCase = true)) return cleanUrl
    if (!originalScheme.equals("https", ignoreCase = true)) return cleanUrl
    return "https://" + cleanUrl.substringAfter("://")
}
