package org.jetbrains.kotlin.backend.konan.thexport.thast

abstract class Decl(val annotations: Annotations?) {

}

class Parameter(annotations: Annotations?, val name : String, val type : Type) : Decl(annotations) {
    override fun toString(): String {
        val annos : String = annotations?.toString() ?: ""
        return if (annotations == null) "${name}: ${type}" else "${annos} ${name}: ${type}"
    }
}

class FunDecl(annotations: Annotations?,
              val name: String, val parameters : List<Parameter>,
              val retType: Pair<Annotation?, Type>) : Decl(annotations) {
    override fun toString(): String {
        val funAnno = annotations.toString()
        val retAnno = if (retType.first == null) "" else "[${retType.first?.toString()}]"
        val funStr = "function $name(${parameters.joinToString(",") { it.toString() }}): ${retAnno} ${retType.second}"
        return if (annotations == null) funStr else listOf(funAnno, funStr).joinToString("\n")
    }
}

class ClassDecl(annotations: Annotations?,
                val name: String,
                val fields : List<Parameter>,
                val functions : List<FunDecl>) {


}