package io.executionbridge.android

import android.app.Activity
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject

/** Explicit import/approval/export only. No network or background execution. */
class ChallengeActivity:Activity() {
    private lateinit var input:EditText
    private lateinit var output:TextView
    private lateinit var share:Button
    private lateinit var save:Button
    private var response:String?=null
    private var exportPending=false
    private var frozen:String?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        response=state?.getString("response")
        frozen=state?.getString("frozen")
        exportPending=state?.getBoolean("exportPending") ?: false
        val col=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(32,32,32,32)}
        fun label(s:String)=TextView(this).apply {text=s;col.addView(this)}
        fun button(s:String,f:()->Unit)=Button(this).apply {text=s;setOnClickListener {f()};col.addView(this)}
        label("Проверка связи с ChatGPT — ручной обмен")
        label("Вставьте код EB1 из текущего чата. По вашему нажатию будет прочитан только актуальный заряд. Код не подтверждает личность отправителя. Никакой автоматической отправки.")
        input=EditText(this).apply {hint="EB1:…";isSingleLine=false;isSaveEnabled=false;col.addView(this)}
        button("Прочитать заряд и сформировать ответ") {executeChallenge()}
        output=label("")
        save=button("Сохранить ответ JSON") {
            if(exportPending) return@button
            val body=response ?: return@button
            frozen=body;exportPending=true;render()
            try {startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE);type="application/json"
                putExtra(Intent.EXTRA_TITLE,"ExecutionBridge-battery-response.json")
            },21)} catch(_:Exception) {exportPending=false;frozen=null;render();message("Выбор файла недоступен.")}
        }
        share=button("Поделиться ответом") {
            val body=response ?: return@button
            try {startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type="text/plain";putExtra(Intent.EXTRA_TEXT,body)
            },"Выберите получателя"))} catch(_:Exception) {message("Сохраните JSON вместо отправки.")}
        }
        button("Очистить ответ") {if(!exportPending){response=null;render()}}
        val scroll=ScrollView(this).apply {addView(col)}
        setContentView(scroll);applySystemInsets(scroll);render()
    }
    private fun message(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun render() {
        output.text=response ?: "Ответ ещё не сформирован."
        share.isEnabled=response!=null && !exportPending
        save.isEnabled=response!=null && !exportPending
    }
    private fun executeChallenge() {
        if(exportPending) return
        response=null;render()
        try {
            val now=System.currentTimeMillis()
            val challenge=BatteryChallenge.parse(input.text.toString(),now)
            val prefs=getSharedPreferences("battery_challenge_replay",MODE_PRIVATE)
            val entries=prefs.all
            val live=entries.filterValues {it is Long && it>=now}
            require(challenge.id !in live && live.size<256)
            val battery=getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val captured=System.currentTimeMillis()
            val result=challenge.response(battery,captured,BuildConfig.VERSION_NAME).toString(2)
            val edit=prefs.edit()
            entries.keys.filter {it !in live}.forEach {edit.remove(it)}
            // Persist consumption before exposing the result. Expired entries are pruned.
            check(edit.putLong(challenge.id,challenge.expires).commit())
            response=result;input.text.clear();render()
        } catch(_:Exception) {
            message("Запрос отклонён: неверный, просроченный, повторный или заряд недоступен.")
        }
    }
    override fun onSaveInstanceState(out:Bundle) {
        super.onSaveInstanceState(out)
        out.putString("response",response);out.putString("frozen",frozen)
        out.putBoolean("exportPending",exportPending)
    }
    @Deprecated("Activity result API retained for dependency-free build")
    override fun onActivityResult(request:Int,code:Int,data:Intent?) {
        super.onActivityResult(request,code,data)
        if(request!=21 || !exportPending) return
        val body=frozen;frozen=null;exportPending=false;render()
        val uri=data?.data
        if(code!=RESULT_OK || uri==null || body==null) return
        // Bounded response, written only to an owner-selected URI.
        val ok=runCatching {requireNotNull(contentResolver.openOutputStream(uri,"wt")).use {it.write(body.toByteArray(Charsets.UTF_8))}}.isSuccess
        message(if(ok) "Ответ сохранён. Прикрепите его в исходный чат." else "Сохранение не подтверждено.")
    }
}
