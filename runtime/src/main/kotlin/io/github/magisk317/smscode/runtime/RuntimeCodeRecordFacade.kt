package io.github.magisk317.smscode.runtime

import android.content.Context
import android.net.Uri
import io.github.magisk317.xposed.logging.XLog
import io.github.magisk317.smscode.data.db.DBManager
import io.github.magisk317.smscode.data.db.entity.SmsMsg
import io.github.magisk317.smscode.runtime.bridge.UiCodeRecordAccess
import io.github.magisk317.smscode.runtime.contract.record.SmsMsgRecordData
import io.github.magisk317.smscode.runtime.common.utils.JsonUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object RuntimeCodeRecordFacade : UiCodeRecordAccess {

    override fun recordsFlow(context: Context): Flow<List<SmsMsgRecordData>> {
        return DBManager.get(context).queryAllSmsMsgFlow().map { list -> list.map { it.toRecordFileData() } }
    }

    override fun queryRecords(context: Context): List<SmsMsgRecordData> {
        return DBManager.get(context).queryAllSmsMsg().map { it.toRecordFileData() }
    }

    override suspend fun removeRecords(context: Context, records: List<SmsMsgRecordData>) {
        DBManager.get(context).removeSmsMsgListSuspend(records.map { it.toSmsMsg() })
    }

    override suspend fun restoreRecords(context: Context, records: List<SmsMsgRecordData>) {
        DBManager.get(context).insertSmsMsgListSuspend(records.map { it.toSmsMsg() })
    }

    override fun exportCodeRecords(context: Context, uri: Uri, records: List<SmsMsgRecordData>): Boolean {
        val codeRecords = records.filter {
            it.msgType == SmsMsgRecordData.MSG_TYPE_SMS && !it.smsCode.isNullOrBlank()
        }
        return runCatching {
            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: return@runCatching false
            outputStream.use { os ->
                OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                    JsonUtils.toJson(codeRecords, writer)
                }
            }
            true
        }.onFailure { XLog.e(message = "Export code records failed", it) }
            .getOrDefault(false)
    }

    private fun SmsMsg.toRecordFileData(): SmsMsgRecordData = SmsMsgRecordData(
        id = id,
        sender = sender,
        body = body,
        date = date,
        processedTime = processedTime,
        company = company,
        smsCode = smsCode,
        packageName = packageName,
        notifyChannelId = notifyChannelId,
        simSlot = simSlot,
        subId = subId,
        contactName = contactName,
        phoneArea = phoneArea,
        msgType = msgType,
        callType = callType,
        sessionKey = sessionKey,
    )

    private fun SmsMsgRecordData.toSmsMsg(): SmsMsg = SmsMsg(
        id = id,
        sender = sender,
        body = body,
        date = date,
        processedTime = processedTime,
        company = company,
        smsCode = smsCode,
        packageName = packageName,
        notifyChannelId = notifyChannelId,
        simSlot = simSlot,
        subId = subId,
        contactName = contactName,
        phoneArea = phoneArea,
        msgType = msgType,
        callType = callType,
        sessionKey = sessionKey,
    )
}
