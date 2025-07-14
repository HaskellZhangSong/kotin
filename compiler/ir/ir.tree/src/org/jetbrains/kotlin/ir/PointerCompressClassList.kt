/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir

class PointerCompressClassList {
    companion object {
        /**
         * The list of classes that are pointer-compressed in the IR.
         * This is used to optimize memory usage for certain classes that are frequently used in the IR.
         */
        val pointerCompressedClasses: List<String> = listOf(
            "Person",
        )
    }
}