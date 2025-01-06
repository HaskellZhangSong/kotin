/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.thexport

import org.jetbrains.kotlin.backend.konan.thexport.*
import java.io.File
import org.jetbrains.kotlin.backend.konan.cexport.CAdapterExportedElements
import org.jetbrains.kotlin.backend.konan.driver.PhaseContext
import org.jetbrains.kotlin.backend.common.phaser.createSimpleNamedCompilerPhase

internal data class TaiheGenerateApiInput(
        val elements: CAdapterExportedElements,
        val taiheFile: File
)

internal class TaiheApiExporter(
        private val elements: CAdapterExportedElements,
        private val taiheFile: File
) {
    // generation functions
    fun makeIDL() {
        println("Making idl")
    }
}
internal val TaiheGenerateApiPhase = createSimpleNamedCompilerPhase<PhaseContext, TaiheGenerateApiInput>(
        name = "TaiheExportGenerateApi",
        description = "Create Taihe idl file for the exported API"
) {
    context, input ->
    TaiheApiExporter(elements = input.elements, input.taiheFile).makeIDL()
}