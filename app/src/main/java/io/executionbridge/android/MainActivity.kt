package io.executionbridge.android

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val pool = Executors.newSingleThreadExecutor()
    private val disabled = AtomicBoolean(false)
    private var active: HttpURLConnection? = null
    private val lock = Any()
    private lateinit var status: TextView
    private lateinit var preview: TextView
    private lateinit var endpoint: EditText
    private lateinit var device: EditText
    private lateinit var credential: EditText
    private lateinit var send: Button
    private var snapshot: JSONObject? = null
    private var capturedAt=0L
    private var busy=false
    private lateinit var capture: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32,32,32,32)
        }
        val scroll = ScrollView(this).apply { addView(column) }
        fun label(s: String) = TextView(this).apply { text=s; column.addView(this) }
        fun input(s: String) = EditText(this).apply {
            hint=s; isSingleLine=true; isSaveEnabled=false; column.addView(this)
        }
        fun button(s: String, f: () -> Unit) = Button(this).apply {
            text=s; setOnClickListener { f() }; column.addView(this)
        }
        label("Execution Bridge • "+BuildConfig.VERSION_NAME)
        label("Только чтение. Данные отправляются только по вашему нажатию. Нет фонового управления.")
        button("Локальный пакет обслуживания") {
            startActivity(Intent(this, LocalMaintenanceActivity::class.java))
        }
        label("Gateway (необязательный экспериментальный режим)")
        endpoint=input("https://gateway.example")
        device=input("ID устройства")
        credential=input("Временный токен устройства").apply {
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        preview=label("Сначала сформируйте снимок.")
        status=label("Не подключено")
        capture=button("Сформировать снимок") {
            if(!busy && !disabled.get()) {
                val fresh=health()
                snapshot=fresh
                capturedAt=android.os.SystemClock.elapsedRealtime()
                preview.text=fresh.toString(2)
                send.isEnabled=true
            }
        }
        send=button("Отправить показанный снимок один раз") { submit() }
        send.isEnabled=false
        button("Отключить доступ") { stopAccess() }
        setContentView(scroll)
        applySystemInsets(scroll)
    }

    private fun health(): JSONObject {
        val batt=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level=batt?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1
        val scale=batt?.getIntExtra(BatteryManager.EXTRA_SCALE,-1) ?: -1
        val temperature=batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Int.MIN_VALUE) ?: Int.MIN_VALUE
        val memory=ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val cm=getSystemService(ConnectivityManager::class.java)
        val connected=cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
        return JSONObject().put("model",Build.MODEL.take(80))
            .put("android",Build.VERSION.RELEASE.take(80))
            .put("bridge",BuildConfig.VERSION_NAME)
            .put("battery",if(level>=0 && scale>0) (level*100/scale).coerceIn(0,100) else JSONObject.NULL)
            .put("temperature_tenths_c",if(temperature in -500..1500) temperature else JSONObject.NULL)
            .put("available_ram_bytes",memory.availMem)
            .put("free_disk_bytes",filesDir.usableSpace)
            .put("power_save",getSystemService(PowerManager::class.java).isPowerSaveMode)
            .put("network_connected",connected)
    }

    private fun post(base: URI, token: String, path: String, data: JSONObject): JSONObject {
        val c=base.resolve(path).toURL().openConnection() as HttpURLConnection
        synchronized(lock) {
            check(!disabled.get())
            active=c
        }
        try {
            c.instanceFollowRedirects=false
            c.connectTimeout=10000
            c.readTimeout=10000
            c.requestMethod="POST"
            c.doOutput=true
            c.setRequestProperty("Authorization","Bearer $token")
            c.setRequestProperty("Content-Type","application/json")
            c.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            check(c.responseCode==200)
            val bytes=c.inputStream.use { input ->
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(4096)
                while(true) {
                    val n=input.read(buffer)
                    if(n<0) break
                    check(output.size()+n<=16384)
                    output.write(buffer,0,n)
                }
                output.toByteArray()
            }
            check(bytes.size<=16384)
            return JSONObject(String(bytes,Charsets.UTF_8))
        } finally {
            c.disconnect()
            synchronized(lock) { if(active===c) active=null }
        }
    }
    private fun submit() {
        if(busy || disabled.get()) return
        val shown=snapshot ?: return
        if(android.os.SystemClock.elapsedRealtime()-capturedAt>60000) {
            snapshot=null;send.isEnabled=false;status.text="Снимок устарел. Сформируйте новый.";return
        }
        val base: URI
        val did: String
        val token=credential.text.toString()
        try {
            base=Endpoint.base(endpoint.text.toString())
            did=Endpoint.id(device.text.toString())
            require(token.length in 32..128 && token.all { it.isLetterOrDigit() || it=='_' || it=='-' })
        } catch (_:Exception) {
            status.text="Проверьте HTTPS-адрес, ID и токен."
            return
        }
        credential.text.clear()
        snapshot=null
        busy=true;capture.isEnabled=false
        send.isEnabled=false
        status.text="Запрос ожидающих операций…"
        pool.execute {
            val message=try {
                val poll=post(base,token,"/v1/poll",JSONObject().put("device_id",did))
                if(poll.isNull("operation")) "Нет ожидающих запросов."
                else {
                    val op=poll.getJSONObject("operation")
                    check(op.getString("type")=="bridge.get_device_health")
                    check(op.getString("status")=="PENDING")
                    val oid=Endpoint.id(op.getString("operation_id"))
                    val response=post(base,token,"/v1/finish",JSONObject().put("device_id",did).put("operation_id",oid).put("health",shown))
                    check(response.getString("operation_id")==oid && response.getString("status")=="SUCCEEDED")
                    "Сервер подтвердил снимок: $oid"
                }
            } catch (_:Exception) { "Операция не подтверждена. Проверьте состояние на сервере." }
            runOnUiThread { if(!disabled.get() && !isFinishing && !isDestroyed) {busy=false;capture.isEnabled=true;status.text=message} }
        }
    }
    private fun stopAccess() {
        disabled.set(true)
        synchronized(lock) { active?.disconnect() }
        credential.text.clear()
        snapshot=null
        preview.text="Снимок очищен"
        send.isEnabled=false
        capture.isEnabled=false
        status.text="Доступ отключён локально. Для отзыва токена используйте Gateway."
    }
    override fun onDestroy() {
        stopAccess()
        pool.shutdownNow()
        super.onDestroy()
    }
}
