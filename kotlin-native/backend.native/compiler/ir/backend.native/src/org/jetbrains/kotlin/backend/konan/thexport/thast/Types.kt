package org.jetbrains.kotlin.backend.konan.thexport.thast


abstract class Type {

}

enum class BuiltInTypeKind(i: Int) {
    VOID(0), BOOL(1), INTGER(2), FLOAT(3), STRING(10)
}

open class BuiltInType(val name : String, val kind : BuiltInTypeKind) : Type() {
    override fun toString(): String {
        return this.name
    }
}

class ScalarType(
    name: String, kind : BuiltInTypeKind,
    val width : Int, val isSigned : Boolean, val isFloat : Boolean) : BuiltInType(name, kind) {
    override fun toString(): String {
        return name
    }
}

object PrimTypes {
    val VOID = BuiltInType("void", BuiltInTypeKind.VOID)
    val BOOL = BuiltInType("bool", BuiltInTypeKind.BOOL)
    val STRING = BuiltInType("string", BuiltInTypeKind.STRING)
    val I8 =  ScalarType("i8", BuiltInTypeKind.INTGER, 8,true, false)
    val I16 = ScalarType("i16", BuiltInTypeKind.INTGER, 16,true, false)
    val I32 = ScalarType("i32", BuiltInTypeKind.INTGER, 32,true, false)
    val I64 = ScalarType("i64", BuiltInTypeKind.INTGER, 64,true, false)
    val U8 = ScalarType("u8", BuiltInTypeKind.INTGER, 8,   false, false)
    val U16 = ScalarType("u16", BuiltInTypeKind.INTGER, 16,false, false)
    val U32 = ScalarType("u32", BuiltInTypeKind.INTGER, 32,false, false)
    val U64 = ScalarType("u64", BuiltInTypeKind.INTGER, 64,false, false)
    val F32 = ScalarType("f32", BuiltInTypeKind.FLOAT, 32, true, true)
    val F64 = ScalarType("f64", BuiltInTypeKind.FLOAT, 64, true, true)
}

class RefType(val scope: List<String>, val typeName : String) {
    override fun toString(): String {
        return scope.joinToString(".") + "." + typeName
    }
}
