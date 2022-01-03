/*
 * Copyright (C) 2021 Patrick Goldinger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.res.cache

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.common.android.query
import dev.patrickgold.florisboard.common.android.readToFile
import dev.patrickgold.florisboard.ime.nlp.NATIVE_NULLPTR
import dev.patrickgold.florisboard.res.FileRegistry
import dev.patrickgold.florisboard.res.ZipUtils
import dev.patrickgold.florisboard.res.ext.Extension
import dev.patrickgold.florisboard.res.ext.ExtensionDefaults
import dev.patrickgold.florisboard.res.ext.ExtensionJsonConfig
import dev.patrickgold.florisboard.res.io.FsDir
import dev.patrickgold.florisboard.res.io.FsFile
import dev.patrickgold.florisboard.res.io.readJson
import dev.patrickgold.florisboard.res.io.subDir
import dev.patrickgold.florisboard.res.io.subFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.*

class CacheManager(context: Context) {
    companion object {
        private const val ImporterDirName = "importer"
        private const val ImporterInputDirName = "input"
        private const val ImporterOutputDirName = "output"

        private const val ExporterDirName = "exporter"

        private const val EditorDirName = "editor"
    }

    private val appContext by context.appContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val importer = WorkspacesContainer<ImporterWorkspace>(ImporterDirName)
    val exporter = WorkspacesContainer<ExporterWorkspace>(ExporterDirName)
    val editor = WorkspacesContainer<EditorWorkspace>(EditorDirName)

    fun readFromUriIntoCache(uri: Uri) = readFromUriIntoCache(listOf(uri))

    fun readFromUriIntoCache(uriList: List<Uri>) = runCatching<ImporterWorkspace> {
        val contentResolver = appContext.contentResolver ?: error("Content resolver is null.")
        val workspace = ImporterWorkspace(uuid = UUID.randomUUID().toString()).also { it.mkdirs() }
        workspace.inputFileInfos = buildList {
            for (uri in uriList) {
                val info = contentResolver.query(uri)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    cursor.moveToFirst()
                    val file = workspace.inputDir.subFile(cursor.getString(nameIndex))
                    contentResolver.readToFile(uri, file)
                    val ext = runCatching {
                        val extWorkingDir = workspace.outputDir.subDir(file.nameWithoutExtension)
                        ZipUtils.unzip(srcFile = file, dstDir = extWorkingDir)
                        val extJsonFile = extWorkingDir.subFile(ExtensionDefaults.MANIFEST_FILE_NAME)
                        extJsonFile.readJson<Extension>(ExtensionJsonConfig)
                    }
                    FileInfo(
                        file = file,
                        mediaType = FileRegistry.guessMediaType(file, contentResolver.getType(uri)),
                        size = cursor.getLong(sizeIndex),
                        ext = ext.getOrNull(),
                    )
                } ?: error("Unable to fetch info about one or more resources to be imported.")
                add(info)
            }
        }
        importer.add(workspace)
        return@runCatching workspace
    }

    inner class WorkspacesContainer<T : Workspace> internal constructor(val dirName: String) {
        private val workspacesGuard = Mutex(locked = false)
        private val workspaces = mutableListOf<T>()

        val dir: FsDir = appContext.cacheDir.subDir(dirName)

        internal fun add(workspace: T) = scope.launch {
            workspacesGuard.withLock {
                workspaces.add(workspace)
            }
        }

        internal fun remove(workspace: T) = scope.launch {
            workspacesGuard.withLock {
                workspaces.remove(workspace)
            }
        }

        fun getWorkspaceByUuid(uuid: String) = runBlocking { getWorkspaceByUuidAsync(uuid).await() }

        fun getWorkspaceByUuidAsync(uuid: String): Deferred<T?> = scope.async {
            workspacesGuard.withLock {
                workspaces.find { it.uuid == uuid }
            }
        }
    }

    abstract inner class Workspace(val uuid: String) : Closeable {
        abstract val dir: FsDir

        open fun mkdirs() {
            dir.mkdirs()
        }

        override fun close() {
            dir.deleteRecursively()
        }
    }

    inner class ImporterWorkspace(uuid: String) : Workspace(uuid) {
        override val dir: FsDir = importer.dir.subDir(uuid)

        val inputDir: FsDir = dir.subDir(ImporterInputDirName)
        val outputDir: FsDir = dir.subDir(ImporterOutputDirName)

        var inputFileInfos = emptyList<FileInfo>()

        override fun mkdirs() {
            super.mkdirs()
            inputDir.mkdirs()
            outputDir.mkdirs()
        }

        override fun close() {
            super.close()
            importer.remove(this)
        }
    }

    inner class ExporterWorkspace(uuid: String) : Workspace(uuid) {
        override val dir: FsDir = exporter.dir.subDir(uuid)
    }

    inner class EditorWorkspace(uuid: String) : Workspace(uuid) {
        override val dir: FsDir = editor.dir.subDir(uuid)
    }

    data class FileInfo(
        val file: FsFile,
        val mediaType: String?,
        val size: Long,
        val ext: Extension?,
        var skipReason: Int = NATIVE_NULLPTR,
    )
}
