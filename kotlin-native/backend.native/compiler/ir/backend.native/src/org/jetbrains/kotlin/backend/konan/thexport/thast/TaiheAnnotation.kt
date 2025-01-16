package org.jetbrains.kotlin.backend.konan.thexport.thast

import java.util.Optional

class Annotation(val ident: String, val args: List<String>?) {
    override fun toString(): String {
        return if (args == null) {
            ident
        } else {
            ident + "(" + args.joinToString(",") + ")"
        }
    }
}
typealias Annotations = List<Annotation>g
//
//class Annotations(val ans : List<Annotation>) {
//    override fun toString(): String {
//        return "[${ans.joinToString(",")}]"
//    }
//}