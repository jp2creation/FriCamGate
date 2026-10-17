package com.nvr.camnory

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.TimeUnit

internal class OnvifCredentials(val username: String, val password: String) {
    override fun toString() = "OnvifCredentials([redacted])"
}
internal data class OnvifProfile(val token: String, val name: String, val encoding: String,
    val ptzConfiguration: String? = null, val ptzNode: String? = null, val videoSource: String? = null,
    val resolution: String? = null)
internal data class OnvifConnection(val deviceEndpoint: String, val mediaEndpoint: String, val profiles: List<OnvifProfile>, val clockOffsetMs: Long)
internal class OnvifHttpException(val statusCode: Int) : IOException("ONVIF HTTP $statusCode")

internal fun onvifEndpoint(value: String): URI {
    val uri = URI(if (value.contains("://")) value.trim() else "http://${value.trim()}")
    require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null) { "Invalid ONVIF endpoint" }
    return if (uri.path.isNullOrEmpty() || uri.path == "/")
        URI(uri.scheme, null, uri.host, uri.port, "/onvif/device_service", null, null) else uri
}

internal fun onvifStreamUri(value: String, endpoint: String, credentials: OnvifCredentials): URI {
    val uri = URI(value)
    require(uri.scheme == "rtsp" && uri.host.equals(URI(endpoint).host, true) && uri.fragment == null) { "Invalid camera stream" }
    // HttpUrl performs component-safe encoding, including reserved password characters.
    val auth = okhttp3.HttpUrl.Builder().scheme("http").host(uri.host)
        .username(credentials.username).password(credentials.password).build()
    val userInfo = if (credentials.username.isEmpty()) "" else "${auth.encodedUsername}:${auth.encodedPassword}@"
    val host = if (uri.host.contains(':')) "[${uri.host}]" else uri.host
    return URI("rtsp://$userInfo$host${if (uri.port >= 0) ":${uri.port}" else ""}${uri.rawPath.orEmpty()}${uri.rawQuery?.let { "?$it" }.orEmpty()}")
}

internal enum class OnvifSoapOperation(val method: String, val namespace: String) {
    Clock("GetSystemDateAndTime", "http://www.onvif.org/ver10/device/wsdl"),
    Services("GetServices", "http://www.onvif.org/ver10/device/wsdl"),
    Profiles("GetProfiles", "http://www.onvif.org/ver10/media/wsdl"),
    Stream("GetStreamUri", "http://www.onvif.org/ver10/media/wsdl"),
    Relays("GetRelayOutputs", "http://www.onvif.org/ver10/deviceIO/wsdl"),
    SetRelay("SetRelayOutputState", "http://www.onvif.org/ver10/deviceIO/wsdl"),
    PtzOptions("GetConfigurationOptions", "http://www.onvif.org/ver20/ptz/wsdl"),
    PtzPresets("GetPresets", "http://www.onvif.org/ver20/ptz/wsdl"),
    PtzMove("RelativeMove", "http://www.onvif.org/ver20/ptz/wsdl"),
    PtzStop("Stop", "http://www.onvif.org/ver20/ptz/wsdl"),
    PtzPreset("GotoPreset", "http://www.onvif.org/ver20/ptz/wsdl"),
    DeviceInfo("GetDeviceInformation", "http://www.onvif.org/ver10/device/wsdl"),
    ImageSettings("GetImagingSettings", "http://www.onvif.org/ver20/imaging/wsdl"),
    ImageOptions("GetOptions", "http://www.onvif.org/ver20/imaging/wsdl"),
    SetImage("SetImagingSettings", "http://www.onvif.org/ver20/imaging/wsdl")
}

/** Closed, read-only SOAP surface. No generic operation or mutation API is exposed. */
internal class OnvifReadOnlyClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).callTimeout(10, TimeUnit.SECONDS).build()) {

    fun connect(endpoint: String, credentials: OnvifCredentials, supportedOnly: Boolean = true): OnvifConnection {
        val address = onvifEndpoint(endpoint).toASCIIString()
        val offset = runCatching {
            val clock = request(address, address, credentials, OnvifSoapOperation.Clock, 0).descendant("UTCDateTime")!!
            val date = clock.descendant("Date")!!
            val time = clock.descendant("Time")!!
            val value = "${date.child("Year")!!.text}-${date.child("Month")!!.text.padStart(2, '0')}-${date.child("Day")!!.text.padStart(2, '0')}T${time.child("Hour")!!.text.padStart(2, '0')}:${time.child("Minute")!!.text.padStart(2, '0')}:${time.child("Second")!!.text.padStart(2, '0')}Z"
            timestampFormat().parse(value)!!.time - System.currentTimeMillis()
        }.getOrDefault(0L)
        val services = request(address, address, credentials, OnvifSoapOperation.Services, offset)
        val media = services.descendants("Service").firstOrNull { it.child("Namespace")?.text == MEDIA }
            ?.child("XAddr")?.text ?: throw IOException("ONVIF Media1 unavailable")
        val profiles = request(address, media, credentials, OnvifSoapOperation.Profiles, offset).descendants("Profiles").mapNotNull {
            val token = it.attributes["token"] ?: return@mapNotNull null
            val encoding = it.descendant("VideoEncoderConfiguration")?.child("Encoding")?.text ?: return@mapNotNull null
            val ptz = it.child("PTZConfiguration")
            OnvifProfile(token, it.child("Name")?.text ?: token, encoding,
                ptz?.attributes?.get("token"), ptz?.child("NodeToken")?.text,
                it.child("VideoSourceConfiguration")?.child("SourceToken")?.text,
                it.descendant("Resolution")?.let { size -> "${size.child("Width")?.text.orEmpty()} × ${size.child("Height")?.text.orEmpty()}" })
        }.filter { !supportedOnly || it.encoding.equals("H264", true) }
        if (profiles.isEmpty()) throw IOException("No supported video profile")
        return OnvifConnection(address, media, profiles, offset)
    }

    fun stream(connection: OnvifConnection, profile: OnvifProfile, credentials: OnvifCredentials): URI {
        require(profile in connection.profiles)
        val result = request(connection.deviceEndpoint, connection.mediaEndpoint, credentials, OnvifSoapOperation.Stream,
            connection.clockOffsetMs, profile.token)
        val uri = result.descendant("Uri")?.text ?: throw IOException("Missing RTSP URI")
        return onvifStreamUri(uri, connection.deviceEndpoint, credentials)
    }

    fun cancel() = http.dispatcher.cancelAll()

    private val transport = OnvifSoapTransport(http)
    private fun request(origin: String, endpoint: String, credentials: OnvifCredentials,
        operation: OnvifSoapOperation, offsetMs: Long, token: String? = null): OnvifXmlNode =
        transport.request(origin, endpoint, credentials, operation, offsetMs, token)

    companion object {
        private const val DEVICE = "http://www.onvif.org/ver10/device/wsdl"
        private const val MEDIA = "http://www.onvif.org/ver10/media/wsdl"
        private const val SCHEMA = "http://www.onvif.org/ver10/schema"
        private const val SOAP = "http://www.w3.org/2003/05/soap-envelope"
        private const val WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
        private const val WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
        private const val WSSE_PROFILE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0"
        private const val WSSE_ENCODING = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0"
        private fun timestampFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
}

internal class OnvifSoapTransport(private val http: OkHttpClient) {
    fun request(origin: String, endpoint: String, credentials: OnvifCredentials,
        operation: OnvifSoapOperation, offsetMs: Long, token: String? = null, relayActive: Boolean? = null,
        ptzMove: OnvifPtzTranslation? = null, presetToken: String? = null,
        imageChange: Pair<String, Double>? = null): OnvifXmlNode {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
        val target = onvifEndpoint(endpoint)
        require(target.host.equals(URI(origin).host, true)) { "Camera service host mismatch" }
        require(URI(origin).scheme != "https" || target.scheme == "https") { "Camera service downgrade refused" }
        val out = StringWriter()
        val xml = XmlPullParserFactory.newInstance().newSerializer().apply { setOutput(out) }
        fun start(ns: String, name: String) { xml.startTag(ns, name) }
        fun end(ns: String, name: String) { xml.endTag(ns, name) }
        fun text(ns: String, name: String, value: String) { start(ns, name); xml.text(value); end(ns, name) }
        xml.setPrefix("s", SOAP); xml.setPrefix("t", operation.namespace); xml.setPrefix("tt", SCHEMA)
        xml.setPrefix("wsse", WSSE); xml.setPrefix("wsu", WSU)
        start(SOAP, "Envelope")
        start(SOAP, "Header")
        if (credentials.username.isNotEmpty() && operation != OnvifSoapOperation.Clock) {
            val nonce = ByteArray(20).also { SecureRandom().nextBytes(it) }
            val created = timestampFormat().format(Date(System.currentTimeMillis() + offsetMs))
            val digest = MessageDigest.getInstance("SHA-1").digest(nonce + created.toByteArray(Charsets.UTF_8) + credentials.password.toByteArray(Charsets.UTF_8))
            start(WSSE, "Security"); start(WSSE, "UsernameToken")
            text(WSSE, "Username", credentials.username)
            start(WSSE, "Password"); xml.attribute(null, "Type", "$WSSE_PROFILE#PasswordDigest"); xml.text(digest.toByteString().base64()); end(WSSE, "Password")
            start(WSSE, "Nonce"); xml.attribute(null, "EncodingType", "$WSSE_ENCODING#Base64Binary"); xml.text(nonce.toByteString().base64()); end(WSSE, "Nonce")
            text(WSU, "Created", created)
            end(WSSE, "UsernameToken"); end(WSSE, "Security")
        }
        end(SOAP, "Header"); start(SOAP, "Body"); start(operation.namespace, operation.method)
        if (operation == OnvifSoapOperation.Services) text(DEVICE, "IncludeCapability", "false")
        if (operation == OnvifSoapOperation.Stream) {
            start(MEDIA, "StreamSetup"); text(SCHEMA, "Stream", "RTP-Unicast")
            start(SCHEMA, "Transport"); text(SCHEMA, "Protocol", "RTSP"); end(SCHEMA, "Transport"); end(MEDIA, "StreamSetup")
            text(MEDIA, "ProfileToken", requireNotNull(token))
        }
        if (operation == OnvifSoapOperation.SetRelay) {
            text(operation.namespace, "RelayOutputToken", requireNotNull(token))
            text(operation.namespace, "LogicalState", if (requireNotNull(relayActive)) "active" else "inactive")
        }
        if (operation == OnvifSoapOperation.PtzOptions) text(operation.namespace, "ConfigurationToken", requireNotNull(token))
        if (operation in setOf(OnvifSoapOperation.PtzPresets, OnvifSoapOperation.PtzMove, OnvifSoapOperation.PtzStop, OnvifSoapOperation.PtzPreset)) {
            text(operation.namespace, "ProfileToken", requireNotNull(token))
        }
        if (operation == OnvifSoapOperation.PtzMove) {
            val move = requireNotNull(ptzMove)
            require(listOf(move.x, move.y, move.z).all { it.isFinite() && it in -0.1..0.1 })
            require((move.panTiltSpace != null) xor (move.zoomSpace != null))
            start(operation.namespace, "Translation")
            if (move.panTiltSpace != null) {
                require(move.panTiltSpace == ONVIF_RELATIVE_PAN_TILT && move.z == 0.0)
                start(SCHEMA, "PanTilt"); xml.attribute(null, "x", move.x.toString()); xml.attribute(null, "y", move.y.toString())
                xml.attribute(null, "space", move.panTiltSpace); end(SCHEMA, "PanTilt")
            } else {
                require(move.zoomSpace == ONVIF_RELATIVE_ZOOM && move.x == 0.0 && move.y == 0.0)
                start(SCHEMA, "Zoom"); xml.attribute(null, "x", move.z.toString())
                xml.attribute(null, "space", requireNotNull(move.zoomSpace)); end(SCHEMA, "Zoom")
            }
            end(operation.namespace, "Translation")
        }
        if (operation == OnvifSoapOperation.PtzStop) {
            text(operation.namespace, "PanTilt", "true"); text(operation.namespace, "Zoom", "true")
        }
        if (operation == OnvifSoapOperation.PtzPreset) text(operation.namespace, "PresetToken", requireNotNull(presetToken))
        if (operation in setOf(OnvifSoapOperation.ImageSettings, OnvifSoapOperation.ImageOptions, OnvifSoapOperation.SetImage)) {
            text(operation.namespace, "VideoSourceToken", requireNotNull(token))
        }
        if (operation == OnvifSoapOperation.SetImage) {
            val (key, value) = requireNotNull(imageChange)
            require(key in ONVIF_IMAGE_FIELDS && value.isFinite())
            start(operation.namespace, "ImagingSettings"); text(SCHEMA, key, value.toString()); end(operation.namespace, "ImagingSettings")
            text(operation.namespace, "ForcePersistence", "true")
        }
        end(operation.namespace, operation.method); end(SOAP, "Body"); end(SOAP, "Envelope"); xml.flush()
        val body = out.toString().toRequestBody("application/soap+xml; charset=utf-8; action=\"${operation.namespace}/${operation.method}\"".toMediaType())
        http.newCall(Request.Builder().url(target.toASCIIString()).post(body)
            .header("SOAPAction", "\"${operation.namespace}/${operation.method}\"").build()).execute().use {
            if (!it.isSuccessful) throw OnvifHttpException(it.code)
            // Keep reads bounded before parsing untrusted XML.
            val source = it.body?.source() ?: throw IOException("Empty ONVIF response")
            val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, 8192)
                if (read < 0) break
                if (buffer.size > 1_048_576) throw IOException("ONVIF response too large")
            }
            val response = onvifXml(buffer.readUtf8())
            if (response.descendant("Fault") != null) throw IOException("ONVIF request rejected")
            return response.descendant(operation.method + "Response") ?: throw IOException("Unexpected ONVIF response")
        }
    }

    companion object {
        private const val DEVICE = "http://www.onvif.org/ver10/device/wsdl"
        private const val MEDIA = "http://www.onvif.org/ver10/media/wsdl"
        private const val SCHEMA = "http://www.onvif.org/ver10/schema"
        private const val SOAP = "http://www.w3.org/2003/05/soap-envelope"
        private const val WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
        private const val WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
        private const val WSSE_PROFILE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0"
        private const val WSSE_ENCODING = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0"
        private fun timestampFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
}

internal class OnvifXmlNode(val name: String, val attributes: Map<String, String>) {
    var text = ""
    val children = mutableListOf<OnvifXmlNode>()
    fun child(name: String) = children.firstOrNull { it.name == name }
    fun descendant(name: String): OnvifXmlNode? = if (this.name == name) this else children.firstNotNullOfOrNull { it.descendant(name) }
    fun descendants(name: String): List<OnvifXmlNode> = (if (this.name == name) listOf(this) else emptyList()) + children.flatMap { it.descendants(name) }
}

internal fun onvifXml(value: String): OnvifXmlNode {
    require(value.length <= 1_048_576)
    val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    parser.setInput(StringReader(value))
    val root = OnvifXmlNode("root", emptyMap())
    val stack = mutableListOf(root)
    var nodes = 0
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.DOCDECL -> throw IOException("XML entities prohibited")
            XmlPullParser.ENTITY_REF -> {
                if (parser.name !in setOf("amp", "lt", "gt", "quot", "apos")) throw IOException("XML entities prohibited")
                stack.last().text += parser.text.orEmpty()
            }
            XmlPullParser.START_TAG -> {
                require(stack.size < 32 && ++nodes <= 10000)
                val node = OnvifXmlNode(parser.name, (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) })
                stack.last().children.add(node); stack.add(node)
            }
            XmlPullParser.TEXT, XmlPullParser.CDSECT -> stack.last().text += parser.text
            XmlPullParser.END_TAG -> { stack.last().text = stack.last().text.trim(); stack.removeAt(stack.lastIndex) }
        }
        parser.nextToken()
    }
    require(stack.size == 1 && root.children.size == 1)
    return root
}
