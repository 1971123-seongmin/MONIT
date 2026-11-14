package com.monit.app.backup.probe

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 테스트 전용 authority. intent-filter가 없어 일반 문서 선택기의 root로 등록하지 않는다. */
class ProbeDocumentsProvider : DocumentsProvider() {
    private class Entry(val file: File) {
        @Volatile var mode = ProbeDocumentMode.NORMAL
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val entered = CountDownLatch(1)
        val pipeWriter = AtomicReference<ParcelFileDescriptor?>()
    }
    private val entries = ConcurrentHashMap<String, Entry>()
    private val folder get() = File(checkNotNull(context).filesDir, "backup-probe-provider-72").canonicalFile

    override fun onCreate(): Boolean {
        check(context?.packageName == PACKAGE)
        return folder.isDirectory || folder.mkdirs()
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        ownUid()
        val columns = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        return MatrixCursor(columns).apply {
            val row = newRow()
            for (column in columns) {
                row.add(
                    when (column) {
                        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID -> "root"
                        Root.COLUMN_TITLE -> "공개 합성 계측"
                        Root.COLUMN_FLAGS -> Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE
                        else -> null
                    },
                )
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        ownUid()
        val entry = if (documentId == "root") null else entry(documentId)
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)
        return MatrixCursor(columns).apply {
            val row = newRow()
            for (column in columns) {
                row.add(
                    when (column) {
                        Document.COLUMN_DOCUMENT_ID -> documentId

                        Document.COLUMN_DISPLAY_NAME -> if (entry == null) "synthetic" else "synthetic.monitbak"

                        Document.COLUMN_MIME_TYPE -> if (entry == null) Document.MIME_TYPE_DIR else "application/octet-stream"

                        Document.COLUMN_FLAGS -> if (entry == null) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE

                        Document.COLUMN_SIZE -> when {
                            entry == null -> 0L
                            entry.mode == ProbeDocumentMode.FALSE_SIZE -> Long.MAX_VALUE
                            else -> entry.file.length()
                        }

                        else -> null
                    },
                )
            }
        }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        ownUid()
        require(parentDocumentId == "root")
        return MatrixCursor(projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        ownUid()
        require(parentDocumentId == "root" && displayName.startsWith("synthetic-"))
        val id = UUID.randomUUID().toString()
        val file = File(folder, id).canonicalFile
        check(file.parentFile == folder && file.createNewFile())
        entries[id] = Entry(file)
        return id
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        ownUid()
        signal?.throwIfCanceled()
        val entry = entry(documentId)
        if (entry.mode == ProbeDocumentMode.OPEN_DENIED) throw SecurityException("Synthetic provider denial")
        if ('w' in mode) {
            entry.writes.incrementAndGet()
            if (entry.mode == ProbeDocumentMode.BROKEN_WRITE) {
                val pipe = ParcelFileDescriptor.createReliablePipe()
                pipe[0].closeWithError("Synthetic write failure")
                return pipe[1]
            }
        } else {
            entry.reads.incrementAndGet()
            if (entry.mode == ProbeDocumentMode.REJECT_REREAD && entry.writes.get() > 0) throw FileNotFoundException("Synthetic re-read denial")
            if (entry.mode == ProbeDocumentMode.BROKEN_READ) {
                val pipe = ParcelFileDescriptor.createReliablePipe()
                pipe[1].closeWithError("Synthetic read failure")
                return pipe[0]
            }
            if (entry.mode == ProbeDocumentMode.STALL_READ) {
                val pipe = ParcelFileDescriptor.createReliablePipe()
                entry.pipeWriter.set(pipe[1])
                signal?.setOnCancelListener { entry.pipeWriter.getAndSet(null)?.closeWithError("Synthetic cancellation") }
                entry.entered.countDown()
                return pipe[0]
            }
        }
        entry.entered.countDown()
        return ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        ownUid()
        if (!method.startsWith("probe.")) return super.call(method, arg, extras)
        val entry = entry(checkNotNull(arg))
        return when (method) {
            "probe.configure" -> Bundle().also { entry.mode = ProbeDocumentMode.valueOf(checkNotNull(extras?.getString("mode"))) }

            "probe.await" -> Bundle().apply { putBoolean("entered", entry.entered.await(10, TimeUnit.SECONDS)) }

            "probe.release" -> Bundle().also { entry.pipeWriter.getAndSet(null)?.closeWithError("Synthetic release") }

            "probe.status" -> Bundle().apply {
                putInt("reads", entry.reads.get())
                putInt("writes", entry.writes.get())
            }

            else -> error("Unknown synthetic provider action")
        }
    }

    private fun entry(id: String): Entry {
        UUID.fromString(id)
        return entries[id] ?: File(folder, id).canonicalFile.let { file ->
            check(file.parentFile == folder && file.isFile)
            Entry(file).also { entries[id] = it }
        }
    }

    private fun ownUid() = check(Binder.getCallingUid() == Process.myUid()) { "Synthetic provider owner required" }

    companion object {
        const val PACKAGE = "com.monit.app.qa.performance"
        const val AUTHORITY = "$PACKAGE.backup.probe"
    }
}
