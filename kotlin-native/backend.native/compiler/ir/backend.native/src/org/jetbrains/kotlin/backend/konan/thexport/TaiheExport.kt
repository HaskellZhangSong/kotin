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
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import java.io.PrintWriter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.descriptors.getPackageFragments
import org.jetbrains.kotlin.backend.konan.driver.phases.PsiToIrContext
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.declarations.impl.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.ir.util.referenceFunction
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.name.isChildOf
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.annotations.argumentValue
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.isEffectivelyPublicApi
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.isUnit
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.KotlinBuiltIns.*
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.serialization.deserialization.descriptors.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.descriptors.*


internal val kotlinAny: IdSignature.CommonSignature = IdSignature.CommonSignature("kotlin", "Any", null, 0, null)

internal data class TaiheGenerateApiInput(
        val elements: CAdapterExportedElements,
        val taiheFile: File
)

// TODO should be componion object of TaiheApiExporter
internal object KotlinPointerTypeUtils {
    private val cPointerFqName = FqName("kotlinx.cinterop.CPointer")
    lateinit var pointerTypeDescriptor: ClassDescriptor

    fun getCPointerClassDescriptor(builtIns: KotlinBuiltIns): ClassDescriptor {
        val packageFragment: PackageViewDescriptor = builtIns.builtInsModule.getPackage(cPointerFqName.parent())
        return packageFragment.memberScope.getContributedClassifier(cPointerFqName.shortName(), NoLookupLocation.FROM_BACKEND)
                as ClassDescriptor
    }

    fun isCPointerType(type: KotlinType): Boolean {
        val notNullableType = TypeUtils.makeNotNullable(type)
        return notNullableType.constructor == pointerTypeDescriptor.typeConstructor
    }
}

internal class TaiheApiExporter(
        private val prefix: String,
        private val elements: CAdapterExportedElements,
        private val taiheFile: File
) {
    private lateinit var outputStreamWriter: PrintWriter
    private val typeTranslator = elements.typeTranslator
    private val builtIns = elements.typeTranslator.builtIns

    init {
        KotlinPointerTypeUtils.pointerTypeDescriptor = KotlinPointerTypeUtils.getCPointerClassDescriptor(builtIns)
    }

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

    private val simpleNameMapping = mapOf(
            "<this>" to "thiz",
            "<set-?>" to "set"
    )

    private fun translateName(name: Name): String {
        val nameString = name.asString()
        return when {
            simpleNameMapping.contains(nameString) -> simpleNameMapping[nameString]!!
            cKeywords.contains(nameString) -> "${nameString}_"
            name.isSpecial -> nameString.replace("[<> ]".toRegex(), "_")
            else -> nameString
        }
    }

    private fun kotlinTypeToTaiheType(ty: KotlinType): Type {
        return when {
            isByte(ty) -> PrimTypes.I8
            isShort(ty) -> PrimTypes.I16
            isInt(ty) -> PrimTypes.I32
            isLong(ty) -> PrimTypes.I64
            isUByte(ty) -> PrimTypes.U8
            isUShort(ty) -> PrimTypes.U16
            isUInt(ty) -> PrimTypes.U32
            isULong(ty) -> PrimTypes.U64
            isFloat(ty) -> PrimTypes.F32
            isDouble(ty) -> PrimTypes.F64
            isString(ty) -> PrimTypes.STRING
            isUnit(ty) -> PrimTypes.VOID
            isBoolean(ty) -> PrimTypes.BOOL
            KotlinPointerTypeUtils.isCPointerType(ty) -> PrimTypes.CPOINTER
            else -> RefType(listOf(), "${ty}")
        }
    }

    fun kotlinFunctionToTaiheFunction(e: ExportedElement, isGlobal: Boolean, belongClass: String? = null, typeKind: ClassKind? = null): FunDecl {
        val containsArkTsString: Boolean = e.declaration.annotations.iterator().asSequence().toList().map { it.toString() }.contains("@ArkTsString")
        val arkTsStringAnnotations = if (containsArkTsString) {
            listOf(Annotation("ArkTsString"))
        } else {
            listOf()
        }
        val constrAnnotations = if (belongClass != null) {
            listOf(Annotation("constructor"), Annotation(typeKind.toString()))
        } else {
            listOf()
        }
        val anno = listOf(Annotation("inner_name", listOf("\"${e.cname}\""))) + arkTsStringAnnotations + constrAnnotations
        val original = e.declaration.original as FunctionDescriptor
        val descriptor = e.declaration.original
        val name = when (descriptor) {
            is ConstructorDescriptor -> "make_" + belongClass
            is PropertyGetterDescriptor -> "get_${descriptor.correspondingProperty.name.asString()}"
            is PropertySetterDescriptor -> "set_${descriptor.correspondingProperty.name.asString()}"
            is FunctionDescriptor -> e.declaration.name
            else -> descriptor.fqNameSafe.shortName().asString()
        }
        val explicitParams = original.explicitParameters
        val params = ArrayList(original.explicitParameters
                .filter { it.type.includeToSignature() }
                .map { Parameter(null, "${translateName(it.name)}", kotlinTypeToTaiheType(it.type)) })
        val returned = kotlinTypeToTaiheType(original.returnType!!)
        val fd: FunDecl = FunDecl(anno, isGlobal, "${name}", params, Pair(null, returned))
        return fd
    }

    fun makeScopeDefinitions(scope: ExportedElementScope) {
        if (!scope.hasNonEmptySubScopes())
            return
        scope.scopes.forEach {
            makeScopeDefinitions(it)
        }
        scope.elements
                .filter {
                    var annoList = it.declaration.annotations.iterator().asSequence().toList().map { it.toString() }
                    annoList.contains("@ArkTsExportFunctionTaihe") || annoList.contains("@ArkTsExportClassTaihe")
                }
                .forEach {
                    when {
                        it.scope.kind == ScopeKind.PACKAGE && it.isFunction -> {
                            val fd: FunDecl = kotlinFunctionToTaiheFunction(it, true)
                            output("${fd.toString(0)}\n")
                            outputStreamWriter.flush()
                        }
                        it.scope.kind == ScopeKind.CLASS && it.isClass -> {
                            val initFunction = null;
                            val cd = it.declaration as DeserializedClassDescriptor
                            val kind = cd.getKind()
                            val anno = listOf(Annotation("object_kind", listOf("\"${kind}\"".lowercase())),
                                    Annotation("type_function", listOf("\"${it.cname}_type\"")))
                            val interfaceName = it.name
                            val functions = it.scope.elements.filter {
                                var annoList = it.declaration.annotations.iterator().asSequence().toList().map { it.toString() }
                                it.isFunction && annoList.contains("@ArkTsExportFunctionTaihe")
                            }.filter {
                                var funDec = it.declaration as DeserializedSimpleFunctionDescriptor
                                funDec.getOverriddenDescriptors().size == 0
                            }
                            val constructors: List<ExportedElement> = it.scope.elements.filter {
                                it.name == "<init>"
                            }
                            val taiheConstructors = constructors.map { func -> kotlinFunctionToTaiheFunction(func, true, it.name, cd.getKind()) }
                            val taiheFunc = functions.map { kotlinFunctionToTaiheFunction(it, false) }
                            val classImpl = it.irSymbol.owner as IrClassImpl
                            val superTypesList = classImpl.superTypes.map {
                                val irSimpleType = it as IrSimpleType
                                val classifier = irSimpleType.classifier as IrClassSymbol
                                val commonSignature = classifier.signature!!.asPublic()
                                val superTypePackage = if (commonSignature?.packageFqName!! == ""
                                        || commonSignature?.packageFqName == "kotlin"
                                        || commonSignature!!.equals(kotlinAny))
                                    listOf()
                                else commonSignature?.packageFqName?.split(".")!!
                                val superTypeName = commonSignature?.declarationFqName!!
                                RefType(superTypePackage, superTypeName)
                            }.filter { !(it.scope.isEmpty() && it.typeName == "Any") }
                            val iface = InterfaceDecl(anno, interfaceName, superTypesList, taiheFunc)
                            output("${iface.toString(0)}")
                            output(taiheConstructors.map { it.toString(0) }.joinToString("\n"))

                            if (cd.getKind().isSingleton) {
                                val instanceFunName: String = it.scope.elements[0].cname + "_instance"
                                val annos = listOf(Annotation("inner_name", listOf("\"${instanceFunName}\"")), Annotation("singleton"))
                                val taiheInstanceFun = FunDecl(annos, true, "instance_" + interfaceName, listOf(), Pair(null, RefType(listOf(), interfaceName)))
                                output(taiheInstanceFun.toString(0))
                            }
                            outputStreamWriter.flush()
                        }
                    }
                }
    }

    fun makePrefixAnnotation() {
        output("[prefix(\"${prefix}\")]")
        outputStreamWriter.flush()
    }

    fun makeIDL() {
        makeGlobalTaiheDecl()
        makePrefixAnnotation()
    }
}

internal val TaiheGenerateApiPhase = createSimpleNamedCompilerPhase<PhaseContext, TaiheGenerateApiInput>(
        name = "TaiheExportGenerateApi",
        description = "Create Taihe idl file for the exported API"
) { context, input ->
    val prefix = context.config.fullExportedNamePrefix.replace("-|\\.".toRegex(), "_")
    TaiheApiExporter(prefix = prefix, elements = input.elements, input.taiheFile).makeIDL()
}