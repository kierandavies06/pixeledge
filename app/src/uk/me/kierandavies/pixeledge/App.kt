package uk.me.kierandavies.pixeledge

import android.app.Application
import android.content.Context
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.hook = { c, cfg -> Sync.push(c, cfg) }
        Light.mirror = { c, fx -> Sync.fire(c, fx) }
        Light.mirrorRing = { c, fx -> Sync.send(c, "/ring", fx.toJson().toString().toByteArray()) }
        Light.mirrorRingStop = { c -> Sync.send(c, "/ring_stop", ByteArray(0)) }
    }
}

/** Mirrors the config to the watch as a Data Layer item; the watch app applies it on arrival. */
object Sync {
    fun push(c: Context, cfg: Config) {
        try {
            val req = PutDataRequest.create("/config").setData(cfg.toJson().toString().toByteArray()).setUrgent()
            Wearable.getDataClient(c.applicationContext).putDataItem(req)
        } catch (e: Exception) {}
    }

    /** Tell every connected watch to play this effect (phone notifications aren't visible to apps on the watch). */
    fun fire(c: Context, fx: Effect) = send(c, "/fire", fx.toJson().toString().toByteArray())

    fun send(c: Context, path: String, data: ByteArray) {
        try {
            val app = c.applicationContext
            Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
                for (n in nodes) Wearable.getMessageClient(app).sendMessage(n.id, path, data)
            }
        } catch (e: Exception) {}
    }
}
