/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir

import java.io.File

class PointerCompressClassList {
    companion object {
        /**
         * The list of classes that are pointer-compressed in the IR.
         * This is used to optimize memory usage for certain classes that are frequently used in the IR.
         */
        val pointerCompressedClasses: MutableList<String> = mutableListOf();

        init {
            val value = System.getenv("KN_PTR_CMP_CLASS")
            if (value != null && value.isNotEmpty()) {
                val lines = File(value).readLines(Charsets.UTF_8)
                pointerCompressedClasses.addAll(lines)
            }
//            val ptr_cmp_types = File("./ptr_cmp.type")
//            if (!ptr_cmp_types.exists()) {
//                ptr_cmp_types.createNewFile()
//                if (!ptr_cmp_types.exists()) {
//                    System.exit(5)
//                }
//            }
//            println("Pointer-compressed classes:")
//            println(pointerCompressedClasses.joinToString("\n"));

//            ptr_cmp_types.appendText("Pointer compressed classes9:\n")
//            ptr_cmp_types.appendText(pointerCompressedClasses.joinToString("\n"))
//            ptr_cmp_types.appendText("\n=========================================================\n")
        }
    }
}
