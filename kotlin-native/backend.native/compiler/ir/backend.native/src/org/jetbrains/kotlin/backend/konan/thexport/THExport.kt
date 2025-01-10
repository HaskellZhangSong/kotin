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
import org.jetbrains.kotlin.backend.konan.cexport.*
import org.jetbrains.kotlin.backend.konan.thexport.thast.*
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import java.io.PrintWriter

import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.descriptors.getPackageFragments
import org.jetbrains.kotlin.backend.konan.driver.phases.PsiToIrContext
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.ir.util.referenceFunction
import org.jetbrains.kotlin.name.isChildOf
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.annotations.argumentValue
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.isEffectivelyPublicApi
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.isUnit

internal data class TaiheGenerateApiInput(
        val elements: CAdapterExportedElements,
        val taiheFile: File
)

internal class TaiheApiExporter(
        private val elements: CAdapterExportedElements,
        private val taiheFile: File
) {
    private lateinit var outputStreamWriter: PrintWriter

    private fun output(string: String) {
        outputStreamWriter.println(string)
    }

    private fun ExportedElementScope.hasNonEmptySubScopes(): Boolean = elements.isNotEmpty() || scopes.any { it.hasNonEmptySubScopes() }
    fun makeGlobalTaiheDecl() {
        val top = this.elements.scopes.first()
        outputStreamWriter = taiheFile.printWriter()
        makeScopeDefinitions(top)
    }

    private fun KotlinType.includeToSignature() = !this.isUnit()

    private fun StrTypeToTaiheType(ty: String): Type {
        return when(ty) {
            "Byte" -> PrimTypes.I8
            "Short" -> PrimTypes.I16
            "Int" -> PrimTypes.I32
            "Bool" -> PrimTypes.BOOL
            "String" -> PrimTypes.STRING
            "Unit" -> PrimTypes.VOID
            "Float" -> PrimTypes.F32
            "Double" -> PrimTypes.F64
            else -> throw Error("not implemented")
        }
    }
    fun makeScopeDefinitions(scope: ExportedElementScope) {
        if (!scope.hasNonEmptySubScopes())
            return
        scope.scopes.forEach {
            makeScopeDefinitions(it)
        }
        scope.elements.forEach {
            when {
                it.isFunction -> {
                    val anno: Annotations = Annotations(listOf(Annotation("inner_name", listOf("${it.cname}"))))
                    val name = it.declaration.name
                    val original = it.declaration.original as FunctionDescriptor
                    val params = ArrayList(original.explicitParameters
                            .filter { it.type.includeToSignature() }
                            .map { Parameter(null, "${it.name}", StrTypeToTaiheType("${it.type}")) })
                    val returned = when {
                        original is ConstructorDescriptor ->
                            throw Error("not implemented")
                        else ->
                            StrTypeToTaiheType("${original.returnType!!}")
                        }
                    val fd: FunDecl = FunDecl(anno, "${name}", params, Pair(null, returned))
                    output("${fd}")
                    outputStreamWriter.flush()
                }
                else -> {}
            }
        }
    }

    fun makeIDL() {
        println("Making idl")
        makeGlobalTaiheDecl()
    }
}
internal val TaiheGenerateApiPhase = createSimpleNamedCompilerPhase<PhaseContext, TaiheGenerateApiInput>(
        name = "TaiheExportGenerateApi",
        description = "Create Taihe idl file for the exported API"
) {
    context, input ->
    TaiheApiExporter(elements = input.elements, input.taiheFile).makeIDL()
}