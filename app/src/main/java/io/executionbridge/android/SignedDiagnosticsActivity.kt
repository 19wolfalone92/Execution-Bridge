package io.executionbridge.android

import android.app.Activity
import android.os.Bundle
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.WindowManager
import android.widget.*
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONObject

/** Offline enrollment, owner-approved read-only operations, and persistent one-shot results. */
class SignedDiagnosticsActivity:Activity() {
    private val alias="execution_bridge_sign_v2"
    private val prefs by lazy {getSharedPreferences("signed_diagnostics_v2",MODE_PRIVATE)}
    private lateinit var input:EditText
    private lateinit var output:TextView
    private lateinit var status:TextView
    private lateinit var save:Button
    private lateinit var share:Button
    private var body:String?=null
    private var pending=false
    private var frozen:String?=null
    private fun store()=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        pending=state?.getBoolean("pending") ?: false
        frozen=state?.getString("frozen")
        body=state?.getString("body") ?: prefs.getString("last_result",null)
        val col=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(32,32,32,32)}
        fun label(s:String)=TextView(this).apply {text=s;col.addView(this)}
        fun button(s:String,f:()->Unit)=Button(this).apply {text=s;setOnClickListener {if(!pending)f()};col.addView(this)}
        label("Подписанная диагностика — ручной транспорт")
        label("Сначала зарегистрируйте открытый ключ в исходном чате. EB2 не удостоверяет отправителя: каждый запрос разрешаете вы. Автоматической связи нет.")
        status=label("")
        button("Создать / показать открытый ключ") {enroll()}
        input=EditText(this).apply {hint="EB2:…";isSaveEnabled=false;col.addView(this)}
        button("Разрешить указанный запрос") {execute()}
        button("Восстановить последний ответ") {body=prefs.getString("last_result",null);render()}
        button("Отозвать локальный ключ") {
            android.app.AlertDialog.Builder(this).setTitle("Отозвать ключ?")
                .setMessage("Старые ответы останутся проверяемыми у получателя. Для удалённого отзыва сообщите ему идентификатор ключа.")
                .setNegativeButton("Отмена",null).setPositiveButton("Отозвать"){_,_->revoke()}.show()
        }
        output=label("")
        save=button("Сохранить JSON") {
            val data=body ?: return@button
            frozen=data;pending=true;render()
            try {startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type="application/json";addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE,"ExecutionBridge-signed.json")
            },31)} catch(_:Exception) {pending=false;frozen=null;render();msg("Выбор файла недоступен.")}
        }
        share=button("Поделиться JSON") {
            val data=body ?: return@button
            try {startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type="text/plain";putExtra(Intent.EXTRA_TEXT,data)
            },"Выберите получателя"))}catch(_:Exception){msg("Сохраните JSON.")}
        }
        val scroll=ScrollView(this).apply {addView(col)}
        setContentView(scroll);applySystemInsets(scroll);render()
    }
    private fun msg(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun render() {
        status.text=if(runCatching {store().containsAlias(alias)}.getOrDefault(false)) "Локальный ключ существует; регистрация на сервере не подтверждена." else "Ключ отсутствует / отозван."
        output.text=body ?: "Нет ответа."
        save.isEnabled=!pending && body!=null;share.isEnabled=!pending && body!=null
    }
    private fun enroll() {
        try {
            val ks=store()
            if(!ks.containsAlias(alias)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256).build())
                }.generateKeyPair()
            }
            val pub=requireNotNull(store().getCertificate(alias)).publicKey.encoded
            body=JSONObject().put("schema","EXECUTION_BRIDGE_PUBLIC_KEY_V2")
                .put("key_id",SignedProtocol.fingerprint(pub)).put("public_key_der",Base64.getEncoder().encodeToString(pub))
                .put("attestation","NOT_VERIFIED").toString(2)
            render()
        }catch(_:Exception){msg("Keystore: создание ключа не подтверждено.")}
    }
    private fun revoke() {
        try {
            store().deleteEntry(alias)
            check(prefs.edit().remove("last_result").commit())
            body=null;frozen=null;input.text.clear();render()
        }catch(_:Exception){body=null;render();msg("Отзыв не подтверждён полностью.")}
    }
    private fun execute() {
        try {
            val now=System.currentTimeMillis()
            val q=SignedProtocol.parse(input.text.toString(),now)
            val ks=store()
            val pub=requireNotNull(ks.getCertificate(alias)).publicKey.encoded
            require(q.fingerprint==SignedProtocol.fingerprint(pub))
            // Serialized on the main looper, durable consumption and result in a single commit.
            val entries=prefs.all.filterKeys {it.startsWith("seen:")}
            val live=entries.filterValues {it is Long && it>=now}
            val marker="seen:"+q.fingerprint+":"+q.id
            require(marker !in live && live.size<256)
            val value=when(q.operation) {
                "GET_BATTERY_STATUS" -> getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString()
                "GET_STORAGE_STATUS" -> filesDir.usableSpace.toString()
                "GET_NETWORK_STATUS" -> {
                    val cm=getSystemService(ConnectivityManager::class.java)
                    if(cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true) "VALIDATED" else "NOT_VALIDATED"
                }
                "GET_BRIDGE_VERSION" -> BuildConfig.VERSION_NAME
                else -> error("unsupported")
            }
            val key=ks.getKey(alias,null) as java.security.PrivateKey
            val result=SignedProtocol.response(q,System.currentTimeMillis(),BuildConfig.VERSION_NAME,value,key).toString(2)
            val edit=prefs.edit()
            entries.keys.filter {it !in live}.forEach {edit.remove(it)}
            check(edit.putLong(marker,q.expires).putString("last_result",result).commit())
            body=result;input.text.clear();render()
        }catch(_:Exception){msg("Отклонено: ключ, время, повтор, формат или недоступный показатель. Старый ответ не изменён.")}
    }
    override fun onSaveInstanceState(out:Bundle) {
        super.onSaveInstanceState(out)
        out.putString("body",body);out.putString("frozen",frozen);out.putBoolean("pending",pending)
    }
    @Deprecated("Activity result API retained for dependency-free build")
    override fun onActivityResult(request:Int,code:Int,data:Intent?) {
        super.onActivityResult(request,code,data)
        if(request!=31 || !pending)return
        val value=frozen;frozen=null;pending=false;render()
        val uri=data?.data
        if(code!=RESULT_OK || uri==null || value==null)return
        val ok=runCatching {requireNotNull(contentResolver.openOutputStream(uri,"wt")).use {it.write(value.toByteArray(Charsets.UTF_8))}}.isSuccess
        msg(if(ok)"JSON сохранён." else "Сохранение не подтверждено.")
    }
}
