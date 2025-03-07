// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.vfilefinder

import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileContent
import org.jetbrains.kotlin.idea.base.psi.fileTypes.KotlinJavaScriptMetaFileType
import org.jetbrains.kotlin.metadata.js.JsProtoBuf
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.JsMetadataVersion
import java.io.ByteArrayInputStream

object KotlinJavaScriptMetaFileIndex : KotlinFileIndexBase<KotlinJavaScriptMetaFileIndex>(KotlinJavaScriptMetaFileIndex::class.java) {
    private val INDEXER = indexer(FileContent::fqNameFromJsMetadata)

    override fun getIndexer() = INDEXER
    override fun getVersion() = 4

    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(KotlinJavaScriptMetaFileType)
}

internal fun FileContent.fqNameFromJsMetadata(): FqName? {
    return ByteArrayInputStream(content).use { stream ->
        if (JsMetadataVersion.readFrom(stream).isCompatible()) {
            FqName(JsProtoBuf.Header.parseDelimitedFrom(stream).packageFqName)
        } else null
    }
}