package com.monit.app.backup.probe

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.security.keystore.KeyInfo
import com.monit.app.backup.KeystoreRecoveryCopyStore
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.backup.BackupProbeSession
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject

internal val probeContexts = object : CoroutineContextProvider {
    override val main = Dispatchers.Main
    override val io = Dispatchers.IO
    override val default = Dispatchers.Default
}

/** 실제 store의 공간 검사에 전용 DB/WAL/SHM을 제공하고 sample별 암호화 사본을 보존한다. */
internal fun probeRecovery(app: Context, session: BackupProbeSession, label: String): KeystoreRecoveryCopyStore {
    require(label.matches(Regex("[a-z0-9-]{1,80}")))
    val folder = File(session.pack.directory, "recovery-$label").canonicalFile
    check(folder.parentFile == session.pack.directory.canonicalFile && !folder.exists() && folder.mkdirs())
    val context = object : ContextWrapper(app) {
        override fun getFilesDir(): File = folder
        override fun getDatabasePath(name: String): File = if (name == "monit-ledger.db") session.databaseFile else super.getDatabasePath(name)
    }
    return KeystoreRecoveryCopyStore(context, probeContexts)
}

/** 최초 정상 protect 이후 자체 UID의 alias만 읽는다. 생성·삭제·encoded key 추출은 하지 않는다. */
internal fun probeKeyObservation(app: Context): JSONObject {
    require(app.packageName == BackupProbeSession.APPLICATION_ID)
    return try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = checkNotNull(store.getKey("monit-backup-recovery-v1", null) as? SecretKey)
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        JSONObject().put("observed", true).put("insideSecureHardware", info.isInsideSecureHardware)
            .put("securityLevel", if (Build.VERSION.SDK_INT >= 31) info.securityLevel else JSONObject.NULL)
            .put("requiredForPass", false)
    } catch (error: Exception) {
        JSONObject().put("observed", false).put("errorClass", error.javaClass.simpleName).put("requiredForPass", false)
    }
}
