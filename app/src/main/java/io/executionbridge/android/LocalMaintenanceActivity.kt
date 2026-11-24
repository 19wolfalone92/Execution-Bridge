package io.executionbridge.android

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

class LocalMaintenanceActivity : Activity() {
    private val executor=Executors.newSingleThreadExecutor()
    private lateinit var result: TextView
    private lateinit var expected: EditText
    private lateinit var export: Button
    private var report: JSONObject?=null
    private var frozenExport: String?=null
    private var frozenHash: String?=null
    private var busy=false
    private var pending=0
    private val controls=mutableListOf<Button>()
    private val allowed=setOf("com.williamsbot","com.williamsbot.debug")

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        report=state?.getString("report")?.let { runCatching { JSONObject(it) }.getOrNull() }
        frozenExport=state?.getString("export")
        frozenHash=state?.getString("hash")
        pending=state?.getInt("pending") ?: 0
        cacheDir.listFiles()?.filter { it.name.startsWith("owner-picked-") && it.lastModified()<System.currentTimeMillis()-3600000 }?.forEach { it.delete() }
        val col=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(32,32,32,32)}
        fun label(s:String)=TextView(this).apply {text=s;col.addView(this)}
        fun button(s:String,f:()->Unit)=Button(this).apply {text=s;setOnClickListener {if(!busy && pending==0) f()};col.addView(this);controls.add(this)}
        label("Локальное обслуживание")
        label("Работает без Gateway. Только явно выбранные файлы и два известных пакета Williams. Ничего не отправляется автоматически.")
        button("Состояние телефона и приложений") {refresh()}
        button("Проверить диагностический ZIP") {pick(11,"application/zip")}
        expected=EditText(this).apply {hint="Доверенный SHA-256 APK из CI";isSingleLine=true;isSaveEnabled=false;col.addView(this)}
        button("Проверить совместимость APK Williams") {
            val hash=expected.text.toString().trim().lowercase()
            if(!hash.matches(Regex("[0-9a-f]{64}"))) {toast("Введите доверенный SHA-256.");return@button}
            frozenHash=hash
            pick(12,"application/vnd.android.package-archive")
        }
        export=button("Сохранить отчёт JSON для ChatGPT") {
            frozenExport=report?.toString(2) ?: return@button
            openPicker(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE);type="application/json"
                putExtra(Intent.EXTRA_TITLE,"ExecutionBridge-report.json")
            },13)
        }
        export.isEnabled=false
        result=label("Сформируйте отчёт.")
        setContentView(ScrollView(this).apply {addView(col)})
        showReport()
    }

    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun pick(code:Int,mime:String) {
        openPicker(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="*/*"
            putExtra(Intent.EXTRA_MIME_TYPES,arrayOf(mime,"application/octet-stream"))
        },code)
    }
    private fun openPicker(intent:Intent, code:Int) {
        pending=code
        updateControls()
        try { startActivityForResult(intent,code) }
        catch (_:android.content.ActivityNotFoundException) {
            pending=0;frozenExport=null;frozenHash=null;updateControls()
            toast("На устройстве нет доступного выбора файлов.")
        }
    }
    override fun onSaveInstanceState(out:Bundle) {
        super.onSaveInstanceState(out)
        out.putString("report",report?.toString())
        out.putString("export",frozenExport)
        out.putString("hash",frozenHash)
        out.putInt("pending",pending)
    }
    private fun updateControls() {
        controls.forEach {it.isEnabled=!busy && pending==0}
        export.isEnabled=!busy && pending==0 && report!=null
        expected.isEnabled=!busy && pending==0
    }
    private fun showReport() {result.text=report?.toString(2) ?: "Сформируйте отчёт.";updateControls()}
    private fun refresh() {
        busy=true;updateControls();result.text="Получение состояния…"
        executor.execute {
            val value=runCatching {baseReport()}.getOrNull()
            runOnUiThread {
                if(isDestroyed || isFinishing) return@runOnUiThread
                busy=false;report=value;showReport()
                if(value==null) toast("Не удалось получить состояние устройства.")
            }
        }
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it.toInt() and 255)}
    @Suppress("DEPRECATION")
    private fun installed(pkg:String):PackageInfo?=try {
        packageManager.getPackageInfo(pkg,if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
    } catch(_:PackageManager.NameNotFoundException) {null}
    @Suppress("DEPRECATION")
    private fun version(info:PackageInfo)=if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION")
    private fun signers(info:PackageInfo):Set<String> {
        val a=if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else info.signatures
        return a?.map {sha(it.toByteArray())}?.toSet() ?: emptySet()
    }
    private fun baseReport():JSONObject {
        val battery=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm=getSystemService(BatteryManager::class.java)
        val mem=ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(mem)
        val pm=getSystemService(PowerManager::class.java)
        val cm=getSystemService(ConnectivityManager::class.java)
        val caps=cm.getNetworkCapabilities(cm.activeNetwork)
        val apps=JSONArray()
        for(pkg in allowed.sorted()) {
            val info=installed(pkg)
            apps.put(JSONObject().put("package",pkg).put("installed",info!=null).apply {
                if(info!=null) {
                    put("version_name",info.versionName ?: JSONObject.NULL)
                    put("version_code",version(info))
                    put("signers_sha256",JSONArray(signers(info).sorted()))
                    put("last_update_ms",info.lastUpdateTime)
                }
                put("runtime_status","NOT_AVAILABLE")
            })
        }
        val temp=battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Int.MIN_VALUE) ?: Int.MIN_VALUE
        return JSONObject().put("schema","EXECUTION_BRIDGE_LOCAL_V1")
            .put("captured_at_ms",System.currentTimeMillis())
            .put("bridge",BuildConfig.VERSION_NAME).put("source","OWNER_INITIATED_LOCAL")
            .put("model",Build.MODEL).put("android",Build.VERSION.RELEASE)
            .put("api_level",Build.VERSION.SDK_INT)
            .put("battery_percent",bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf {it in 0..100} ?: JSONObject.NULL)
            .put("battery_temperature_tenths_c",temp.takeIf {it in -500..1500} ?: JSONObject.NULL)
            .put("ram_available_bytes",mem.availMem).put("ram_total_bytes",mem.totalMem)
            .put("low_memory",mem.lowMemory).put("free_disk_bytes",filesDir.usableSpace)
            .put("power_save",pm.isPowerSaveMode).put("device_idle",pm.isDeviceIdleMode)
            .put("network_validated",caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true)
            .put("network_wifi",caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true)
            .put("network_cellular",caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true)
            .put("apps",apps).put("gateway_state","NOT_CHECKED")
            .put("trading_update_readiness","BLOCKED")
    }
    private fun copyBounded(input:InputStream,target:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        var total=0L
        target.outputStream().use {out ->
            val buf=ByteArray(65536)
            while(true) {
                check(!Thread.currentThread().isInterrupted)
                val n=input.read(buf)
                if(n<0) break
                total+=n
                require(total<=128L*1024*1024 && filesDir.usableSpace>8L*1024*1024)
                digest.update(buf,0,n);out.write(buf,0,n)
            }
        }
        require(total>0)
        return digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
    }
    @Suppress("DEPRECATION")
    private fun verifyApk(file:File,hash:String,trusted:String):JSONObject {
        require(hash==trusted)
        val info=requireNotNull(packageManager.getPackageArchiveInfo(file.absolutePath,
            if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES))
        require(info.packageName in allowed)
        val old=installed(info.packageName)
        val newSigners=signers(info)
        val same=old!=null && newSigners.isNotEmpty() && newSigners==signers(old)
        val newer=old!=null && version(info)>version(old)
        val sdk=info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE
        return JSONObject().put("sha256",hash).put("hash_match",true).put("package",info.packageName)
            .put("version_name",info.versionName ?: JSONObject.NULL).put("version_code",version(info))
            .put("installed_version_code",old?.let {version(it)} ?: JSONObject.NULL)
            .put("certificate_match",same).put("strictly_newer",newer)
            .put("minimum_sdk",sdk).put("minimum_sdk_compatible",sdk<=Build.VERSION.SDK_INT)
            .put("signers_sha256",JSONArray(newSigners.sorted()))
            .put("ci_attestation","NOT_VERIFIED").put("installation","BLOCKED")
            .put("reason","Full CI provenance, platform installation and fresh trading readiness not verified")
    }
    @Deprecated("Activity result API retained for dependency-free build")
    override fun onActivityResult(request:Int,code:Int,data:Intent?) {
        super.onActivityResult(request,code,data)
        val uri=data?.data
        if(request !in 11..13 || request!=pending) return
        pending=0
        if(code!=RESULT_OK || uri==null) {frozenExport=null;frozenHash=null;updateControls();return}
        busy=true;updateControls()
        result.text="Проверка файла…"
        val exportText=frozenExport;frozenExport=null
        val trusted=frozenHash;frozenHash=null
        executor.execute {
            var temp:File?=null
            val outcome=runCatching {
                if(request==13) {
                    requireNotNull(contentResolver.openOutputStream(uri,"wt")).use {it.write(requireNotNull(exportText).toByteArray(Charsets.UTF_8))}
                    null
                } else {
                    val f=File.createTempFile("owner-picked-",if(request==12) ".apk" else ".zip",cacheDir);temp=f
                    val hash=requireNotNull(contentResolver.openInputStream(uri)).use {copyBounded(it,f)}
                    if(request==11) ArchiveVerifier.inspect(f,hash) else verifyApk(f,hash,requireNotNull(trusted))
                }
            }
            temp?.delete()
            val fresh=if(request!=13) runCatching {baseReport()}.getOrNull() else null
            runOnUiThread {
                if(isDestroyed || isFinishing) return@runOnUiThread
                busy=false
                if(request==13) toast(if(outcome.isSuccess) "Отчёт сохранён." else "Сохранение не подтверждено.")
                else {
                    report=(fresh ?: JSONObject().put("schema","EXECUTION_BRIDGE_LOCAL_V1").put("trading_update_readiness","BLOCKED").put("device_status","NOT_AVAILABLE")).put(if(request==11) "archive" else "apk_preflight",
                        outcome.getOrNull() ?: JSONObject().put("status","REJECTED").put("readiness","BLOCKED").put("error_code",when(outcome.exceptionOrNull()) { is java.io.IOException -> "FILE_IO_OR_CORRUPT"; is IllegalArgumentException -> "INVALID_FILE_OR_HASH"; is SecurityException -> "ACCESS_DENIED"; else -> "CHECK_FAILED" }))
                }
                showReport()
            }
        }
    }
    override fun onDestroy() {executor.shutdownNow();super.onDestroy()}
}
