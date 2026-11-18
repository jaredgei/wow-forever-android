package app.gamenative.utils

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * The update has no valid build attestation ([checked]), or the check could not run. The app must not install the file.
 */
class UnverifiedUpdateException(message: String, val checked: Boolean) : IOException(message)

/**
 * Checks that an update APK was built by this repository's release workflow on GitHub Actions.
 *
 * The release workflow publishes a build provenance attestation for each APK: a statement with the APK's SHA-256,
 * signed with a short-lived certificate that Sigstore's certificate authority (Fulcio) gives only to a GitHub Actions
 * run. The certificate names the repository, the workflow file and the branch of that run. This check accepts an APK
 * only when such a statement exists for the bytes that were downloaded, the certificate comes from Fulcio, it names
 * [RELEASE], and its key signed the statement. An APK that someone uploaded by hand, or built with another workflow
 * or from another branch, fails.
 *
 * Not checked: the transparency log entry (Rekor). The certificate's 10-minute lifetime is not compared with a trusted
 * signing time; the certificate itself is the proof that the workflow run asked for it.
 */
object ReleaseAttestation {
    /** The workflow run that an attestation's certificate must name. */
    data class Policy(val repository: String, val workflow: String, val ref: String) {
        val signer: String get() = "$repository/$workflow@$ref"
    }

    val RELEASE = Policy(
        repository = "https://github.com/jaredgei/wow-forever-android",
        workflow = ".github/workflows/release.yml",
        ref = "refs/heads/main",
    )

    private const val API = "https://api.github.com/repos/jaredgei/wow-forever-android/attestations"
    private const val PROVENANCE = "https://slsa.dev/provenance/v1"
    private const val STATEMENT = "https://in-toto.io/Statement/v1"
    private const val PAYLOAD_TYPE = "application/vnd.in-toto+json"
    private const val ACTIONS_ISSUER = "https://token.actions.githubusercontent.com"

    // Fulcio's certificate extensions: https://github.com/sigstore/fulcio/blob/main/docs/oid-info.md
    private const val OID_ISSUER = "1.3.6.1.4.1.57264.1.8"
    private const val OID_BUILD_SIGNER = "1.3.6.1.4.1.57264.1.9"
    private const val OID_SOURCE_REPOSITORY = "1.3.6.1.4.1.57264.1.12"

    private const val MAX_BUNDLE_BYTES = 4 * 1024 * 1024

    /**
     * The intermediate certificate of Sigstore's public Fulcio instance (O=sigstore.dev, CN=sigstore-intermediate),
     * valid until 2031-10-05, from Sigstore's trusted root (`gh attestation trusted-root`). GitHub signs attestations
     * of public repositories with certificates from it. If Sigstore replaces it, add the new one here.
     *
     * To check the text below: it is the certificate in base64, and it is the same text as the first `rawBytes` entry
     * for https://fulcio.sigstore.dev in the output of `gh attestation trusted-root`. Its SHA-256 fingerprint is
     * 15:D7:95:34:82:26:B4:64:9F:75:0F:58:02:59:2C:39:3B:EE:7C:C5:3C:3B:86:98:21:75:B7:AD:08:7E:FE:47.
     */
    private val FULCIO_INTERMEDIATES: List<X509Certificate> by lazy {
        listOf(
            "MIICGjCCAaGgAwIBAgIUALnViVfnU0brJasmRkHrn/UnfaQwCgYIKoZIzj0EAwMw" +
                "KjEVMBMGA1UEChMMc2lnc3RvcmUuZGV2MREwDwYDVQQDEwhzaWdzdG9yZTAeFw0y" +
                "MjA0MTMyMDA2MTVaFw0zMTEwMDUxMzU2NThaMDcxFTATBgNVBAoTDHNpZ3N0b3Jl" +
                "LmRldjEeMBwGA1UEAxMVc2lnc3RvcmUtaW50ZXJtZWRpYXRlMHYwEAYHKoZIzj0C" +
                "AQYFK4EEACIDYgAE8RVS/ysH+NOvuDZyPIZtilgUF9NlarYpAd9HP1vBBH1U5CV7" +
                "7LSS7s0ZiH4nE7Hv7ptS6LvvR/STk798LVgMzLlJ4HeIfF3tHSaexLcYpSASr1kS" +
                "0N/RgBJz/9jWCiXno3sweTAOBgNVHQ8BAf8EBAMCAQYwEwYDVR0lBAwwCgYIKwYB" +
                "BQUHAwMwEgYDVR0TAQH/BAgwBgEB/wIBADAdBgNVHQ4EFgQU39Ppz1YkEZb5qNjp" +
                "KFWixi4YZD8wHwYDVR0jBBgwFoAUWMAeX5FFpWapesyQoZMi0CrFxfowCgYIKoZI" +
                "zj0EAwMDZwAwZAIwPCsQK4DYiZYDPIaDi5HFKnfxXx6ASSVmERfsynYBiX2X6SJR" +
                "nZU84/9DZdnFvvxmAjBOt6QpBlc4J/0DxvkTCqpclvziL6BCCPnjdlIB3Pu3BxsP" +
                "mygUY7Ii2zbdCdliiow=",
        ).map { certificate(Base64.getDecoder().decode(it)) }
    }

    /**
     * Passes when GitHub has a valid attestation from the release workflow for the bytes of [apk]. Throws
     * [UnverifiedUpdateException] otherwise, also when GitHub can't be reached: an unchecked file isn't installed.
     */
    suspend fun verify(apk: File, policy: Policy = RELEASE) = withContext(Dispatchers.IO) {
        val digest = sha256(apk)
        val bundles = try {
            fetchBundles(digest)
        } catch (e: Exception) {
            throw UnverifiedUpdateException(
                "The app couldn't check where this update was built (${e.message}). Try again later.",
                checked = false,
            )
        }
        var failure = "GitHub has no build attestation for this file."
        for (bundle in bundles) {
            try {
                verifyBundle(bundle, digest, policy)
                return@withContext
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            }
        }
        throw UnverifiedUpdateException(
            "This update isn't certified as built by the project's release workflow, so the app didn't install it. $failure",
            checked = true,
        )
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Checks one Sigstore bundle (the JSON text) against the file digest [sha256] (lowercase hex) and [policy].
     * Throws when any part fails.
     */
    fun verifyBundle(bundleJson: String, sha256: String, policy: Policy = RELEASE) {
        val bundle = JSONObject(bundleJson)
        val leaf = certificate(decode(bundle.getJSONObject("verificationMaterial").getJSONObject("certificate").getString("rawBytes")))

        // 1. Fulcio issued the certificate.
        require(leaf.basicConstraints == -1) { "The attestation's certificate isn't a signing certificate." }
        val issued = FULCIO_INTERMEDIATES.any { intermediate ->
            leaf.issuerX500Principal == intermediate.subjectX500Principal &&
                runCatching {
                    leaf.verify(intermediate.publicKey)
                    intermediate.checkValidity(leaf.notBefore)
                }.isSuccess
        }
        require(issued) { "The attestation's certificate doesn't come from Sigstore." }

        // 2. Fulcio issued it to the release workflow of this repository, running on the expected branch.
        require(extension(leaf, OID_ISSUER) == ACTIONS_ISSUER) { "The attestation wasn't made by GitHub Actions." }
        require(extension(leaf, OID_SOURCE_REPOSITORY) == policy.repository) { "The attestation is from another repository." }
        require(extension(leaf, OID_BUILD_SIGNER) == policy.signer) {
            "The attestation is from another workflow or branch than ${policy.workflow} on ${policy.ref}."
        }

        // 3. The certificate's key signed the statement (a DSSE envelope: the signature covers the type and the payload).
        val envelope = bundle.getJSONObject("dsseEnvelope")
        val payloadType = envelope.getString("payloadType")
        require(payloadType == PAYLOAD_TYPE) { "The attestation has an unknown payload type." }
        val payload = decode(envelope.getString("payload"))
        val signed = "DSSEv1 ${payloadType.length} $payloadType ${payload.size} ".toByteArray(Charsets.US_ASCII) + payload
        val signatures = envelope.getJSONArray("signatures")
        val validSignature = (0 until signatures.length()).any { index ->
            runCatching {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(leaf.publicKey)
                    update(signed)
                    verify(decode(signatures.getJSONObject(index).getString("sig")))
                }
            }.getOrDefault(false)
        }
        require(validSignature) { "The attestation's signature is wrong." }

        // 4. The statement is build provenance for exactly this file.
        val statement = JSONObject(String(payload, Charsets.UTF_8))
        require(statement.getString("_type") == STATEMENT && statement.getString("predicateType") == PROVENANCE) {
            "The attestation isn't a build provenance statement."
        }
        val subjects = statement.getJSONArray("subject")
        val named = (0 until subjects.length()).any { index ->
            subjects.getJSONObject(index).optJSONObject("digest")?.optString("sha256").equals(sha256, ignoreCase = true)
        }
        require(named) { "The attestation is for another file." }
    }

    /**
     * Decompresses Snappy's block format, which GitHub uses for stored bundles. Input comes from the network: every
     * length and offset is checked, and the output is capped at [MAX_BUNDLE_BYTES].
     */
    fun snappyDecode(input: ByteArray): ByteArray {
        var position = 0
        fun next(): Int {
            if (position >= input.size) throw IOException("Truncated Snappy data")
            return input[position++].toInt() and 0xff
        }
        fun littleEndian(bytes: Int): Int {
            var value = 0
            for (shift in 0 until bytes) value = value or (next() shl (8 * shift))
            if (value < 0) throw IOException("Invalid Snappy data")
            return value
        }
        var size = 0L
        var shift = 0
        while (true) {
            val byte = next()
            size = size or ((byte and 0x7f).toLong() shl shift)
            if (byte < 0x80) break
            shift += 7
            if (shift > 28) throw IOException("Invalid Snappy data")
        }
        if (size > MAX_BUNDLE_BYTES) throw IOException("Snappy data is too large")
        val output = ByteArray(size.toInt())
        var written = 0
        while (position < input.size) {
            val tag = next()
            if (tag and 3 == 0) {
                var length = tag ushr 2
                if (length >= 60) length = littleEndian(length - 59)
                length += 1
                if (length < 1 || length > input.size - position || length > output.size - written) {
                    throw IOException("Invalid Snappy data")
                }
                System.arraycopy(input, position, output, written, length)
                position += length
                written += length
            } else {
                val length: Int
                val offset: Int
                when (tag and 3) {
                    1 -> {
                        length = ((tag ushr 2) and 7) + 4
                        offset = ((tag ushr 5) shl 8) or next()
                    }
                    2 -> {
                        length = (tag ushr 2) + 1
                        offset = littleEndian(2)
                    }
                    else -> {
                        length = (tag ushr 2) + 1
                        offset = littleEndian(4)
                    }
                }
                if (offset < 1 || offset > written || length > output.size - written) throw IOException("Invalid Snappy data")
                // Byte by byte: a copy may overlap the bytes it writes.
                for (index in 0 until length) output[written + index] = output[written + index - offset]
                written += length
            }
        }
        if (written != output.size) throw IOException("Invalid Snappy data")
        return output
    }

    /** The bundles GitHub stores for the file with [digest], as JSON text. Empty when GitHub has none. */
    private fun fetchBundles(digest: String): List<String> {
        val url = "$API/sha256:$digest?predicate_type=${URLEncoder.encode(PROVENANCE, "UTF-8")}"
        val request = Request.Builder().url(url)
            .header("User-Agent", "WoW-Forever-Android")
            .header("Accept", "application/vnd.github+json")
            .build()
        val listing = Net.http.newCall(request).execute().use { response ->
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) throw IOException("GitHub answered HTTP ${response.code}")
            JSONObject(String(limited(response.body?.bytes()), Charsets.UTF_8))
        }
        val attestations = listing.optJSONArray("attestations") ?: return emptyList()
        return (0 until attestations.length()).mapNotNull { index ->
            val attestation = attestations.getJSONObject(index)
            // GitHub returns the bundle inline, or a link to its stored (compressed) copy.
            attestation.optJSONObject("bundle")?.toString()
                ?: attestation.optString("bundle_url").takeIf { it.startsWith("https://") }?.let(::fetchStoredBundle)
        }
    }

    private fun fetchStoredBundle(url: String): String =
        Net.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The attestation store answered HTTP ${response.code}")
            val bytes = limited(response.body?.bytes())
            val compressed = response.header("Content-Type").orEmpty().contains("snappy", ignoreCase = true)
            String(if (compressed) snappyDecode(bytes) else bytes, Charsets.UTF_8)
        }

    private fun limited(bytes: ByteArray?): ByteArray {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BUNDLE_BYTES) throw IOException("Unexpected attestation size")
        return bytes
    }

    private fun certificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private fun decode(base64: String): ByteArray = Base64.getDecoder().decode(base64)

    /** The text of one of Fulcio's extensions: a DER UTF8String inside the extension's OCTET STRING. */
    private fun extension(certificate: X509Certificate, oid: String): String? {
        val outer = certificate.getExtensionValue(oid) ?: return null
        val inner = derContent(outer, 0x04) ?: return null
        return derContent(inner, 0x0c)?.let { String(it, Charsets.UTF_8) }
    }

    /** The content of a DER value that has [tag] and fills [bytes] completely. */
    private fun derContent(bytes: ByteArray, tag: Int): ByteArray? {
        if (bytes.size < 2 || (bytes[0].toInt() and 0xff) != tag) return null
        var position = 1
        var length = bytes[position++].toInt() and 0xff
        if (length >= 0x80) {
            val count = length and 0x7f
            if (count !in 1..3 || bytes.size < position + count) return null
            length = 0
            repeat(count) { length = (length shl 8) or (bytes[position++].toInt() and 0xff) }
        }
        return if (bytes.size - position == length) bytes.copyOfRange(position, bytes.size) else null
    }
}
