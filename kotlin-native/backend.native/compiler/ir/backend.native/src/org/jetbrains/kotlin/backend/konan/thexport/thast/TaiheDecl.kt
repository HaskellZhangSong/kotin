package org.jetbrains.kotlin.backend.konan.thexport.thast

import org.jetbrains.kotlin.backend.konan.thexport.*

abstract class Decl(val annotations: List<Annotation>?) {
    abstract fun toString(indentLevel: Int): String
}

class Parameter(annotations: Annotations?, val name : String, val type : Type) : Decl(annotations) {
    override fun toString(indentLevel: Int): String {
        val annos : String = annotations?.toString() ?: ""
        return if (annotations == null) "${name}: ${type}" else "${annos} ${name}: ${type}"
    }
    override fun toString(): String {
        return this.toString(0)
    }
}

class FunDecl(annotations: Annotations?,
              val isGlobal: Boolean, val name: String, val parameters : List<Parameter>,
              val retType: Pair<Annotation?, Type>) : Decl(annotations) {
    override fun toString(indentLevel: Int): String {
        val funAnno = " ".repeat(indentLevel*2) + annotations.toString()
        val retAnno = if (retType.first == null) "" else "[${retType.first?.toString()}]"
        val funcKW = if (isGlobal) "function " else ""
        val funStr = " ".repeat(indentLevel*2) + "$funcKW$name(${parameters.joinToString(", ") { it.toString() }}):${retAnno} ${retType.second};"
        return if (annotations == null) funStr else listOf(funAnno, funStr).joinToString("\n")
    }
}

class InterfaceDecl(annotations: Annotations?,
                    val name: String,
                    val superTypes: List<Type>,
                    val functions : List<FunDecl>): Decl(annotations) {
    override fun toString(indentLevel: Int): String {
        val funAnno = annotations.toString()
        val superTypeString = if (superTypes.isEmpty()) "" else { ": ${superTypes.joinToString(",")}" }
        val istr = "interface ${name} ${superTypeString} {\n${functions.map {it.toString(indentLevel + 1)}.joinToString("\n")}\n}\n"
        return "${funAnno}\n${istr}"
    }
}