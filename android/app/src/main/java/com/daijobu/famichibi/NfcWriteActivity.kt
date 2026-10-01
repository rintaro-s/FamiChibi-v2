package com.daijobu.famichibi

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daijobu.famichibi.ui.FamiTheme

/** Writes a family invite (famichibi://join?host=..&invite=..) to an NFC card for grandparents. */
class NfcWriteActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private var status by mutableStateOf("カードをスマホの背面にかざしてください")
    private var done by mutableStateOf(false)
    private lateinit var uri: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.getStringExtra("uri") ?: run { finish(); return }
        val label = intent.getStringExtra("label").orEmpty()
        if (NfcAdapter.getDefaultAdapter(this) == null) status = "この端末はNFCに対応していません"
        setContent {
            FamiTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
                        Text(if (done) "✅" else "📇", fontSize = 80.sp)
                        Text("$label の参加カード", style = MaterialTheme.typography.headlineSmall)
                        Text(status, textAlign = TextAlign.Center)
                        Text("書き込んだカードをかざすだけで、アプリのインストール案内→アカウント作成→家族サーバーへの参加まで自動で行います。",
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { finish() }) { Text(if (done) "完了" else "やめる") }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        val msg = NdefMessage(arrayOf(NdefRecord.createUri(uri), NdefRecord.createApplicationRecord(packageName)))
        val result = runCatching {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                if (!ndef.isWritable) error("書き込み禁止のカードです")
                if (ndef.maxSize < msg.toByteArray().size) error("カードの容量が足りません（${ndef.maxSize}バイト）")
                ndef.writeNdefMessage(msg)
                ndef.close()
            } else {
                val f = NdefFormatable.get(tag) ?: error("NDEFに対応していないカードです")
                f.connect(); f.format(msg); f.close()
            }
        }
        runOnUiThread {
            result.onSuccess { done = true; status = "書き込みました！このカードを家族に渡してください" }
                .onFailure { status = "失敗: ${it.message}" }
        }
    }
}
