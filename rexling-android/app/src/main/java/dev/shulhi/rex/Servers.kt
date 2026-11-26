package dev.shulhi.rex

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID

enum class ServerIcon(val key: String,val label: String) {
    GLOBE("globe","Globe"),SERVER("server","Server"),CLOUD("cloud","Cloud"),MACBOOK("macbook","MacBook"),
    MAC_MINI("mac-mini","Mac mini"),MAC_STUDIO("mac-studio","Mac Studio"),DESKTOP("desktop","Desktop");
    companion object {fun decode(value: String?)=entries.firstOrNull {it.key==value} ?: GLOBE}
}

internal enum class ServerNetworkRoute(val key: String,val label: String) {
    PHONE("phone","Phone network / VPN"),BUILTIN("builtin","Built-in Tailscale");
    companion object {fun decode(value: String?)=entries.firstOrNull {it.key==value} ?: PHONE}
}

data class ServerProfile(val id: String,val label: String,val endpoint: String,val icon: ServerIcon=ServerIcon.GLOBE) {
    companion object {
        val Empty=ServerProfile("__empty__","No server","")
        fun create(label: String,endpoint: String,id: String=UUID.randomUUID().toString(),icon: ServerIcon=ServerIcon.GLOBE): ServerProfile {
            val normalized=normalizeEndpoint(endpoint)
            val name=label.trim().ifEmpty {URI(normalized).host.substringBefore('.')}
            require(name.length<=64) {"Use a server name of at most 64 characters"}
            require(name.none {it.isISOControl()}) {"Use a single-line server name"}
            return ServerProfile(id,name,normalized,icon)
        }
        fun normalizeEndpoint(value: String): String {
            val uri=runCatching {URI(value.trim())}.getOrElse {throw IllegalArgumentException("Enter a complete server URL")}
            val scheme=uri.scheme?.lowercase(Locale.ROOT)
            require(scheme in listOf("https","http","wss","ws","ssh") && !uri.host.isNullOrBlank()) {"Enter an HTTP(S), WS(S), or ssh://user@host URL"}
            require((if(scheme=="ssh") !uri.rawUserInfo.isNullOrEmpty() && !uri.rawUserInfo.contains(":") else uri.rawUserInfo==null) && uri.rawQuery==null && uri.rawFragment==null) {"Use the server URL without credentials, query or fragment"}
            require(scheme=="ssh" || uri.path.isNullOrEmpty() || uri.path=="/") {"Use the server's root URL"}
            val port=if((scheme in listOf("https","wss") && uri.port==443) || (scheme in listOf("http","ws") && uri.port==80)) -1 else uri.port
            require(port in -1..65535 && port!=0) {"Enter a valid server port"}
            return URI(scheme,if(scheme=="ssh") uri.userInfo else null,uri.host.lowercase(Locale.ROOT),port,if(scheme=="ssh") uri.path.takeUnless {it=="/"} else null,null,null).toASCIIString()
        }
    }
}

object SavedServers {
    fun decode(text: String?,legacyEndpoint: String): List<ServerProfile> {
        fun legacy() = runCatching {listOf(ServerProfile.create("Rex server",legacyEndpoint,"legacy"))}.getOrDefault(emptyList())
        if(text==null) return legacy()
        return runCatching {
            val array=JSONArray(text)
            (0 until array.length()).mapNotNull { index -> runCatching {
                val item=array.getJSONObject(index)
                ServerProfile.create(item.optString("label"),item.getString("endpoint"),item.getString("id"),ServerIcon.decode(item.optString("icon")))
            }.getOrNull() }.distinctBy {it.id}.distinctBy {it.endpoint}
        }.getOrElse {legacy()}
    }
    fun encode(servers: List<ServerProfile>): String = JSONArray(servers.map {JSONObject().put("id",it.id).put("label",it.label).put("endpoint",it.endpoint).put("icon",it.icon.key)}).toString()
}

data class ServerStatus(val phase: String="disconnected",val error: String="",val version: String="") {
    val connected: Boolean get()=phase=="connected"
    val maintained: Boolean get()=phase in listOf("connected","connecting","reconnecting","paused")
}

/** Materialize legacy app-wide routing once; all subsequent choices belong to endpoints. */
internal fun migratedServerRoutes(routes: JSONObject,endpoints: List<String>,legacyBuiltIn: Boolean): JSONObject {
    val result=JSONObject(routes.toString())
    endpoints.forEach {endpoint->
        if(result.optString(endpoint) !in listOf("phone","builtin")) result.put(endpoint,if(legacyBuiltIn) "builtin" else "phone")
    }
    return result
}
