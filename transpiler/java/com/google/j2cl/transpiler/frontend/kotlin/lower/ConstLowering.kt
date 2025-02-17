/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package com.google.j2cl.transpiler.frontend.kotlin.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.ir.constantValue
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.incremental.components.InlineConstTracker
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

// A pass that lowers constant, this pass is originally from jetbrains but is deprecated.
// Copied and modified from org.jetbrains.kotlin.backend.jvm.lower.ConstLowering.

// MODIFIED BY GOOGLE
// This is unused.
// internal val constPhase1 =
//   makeIrFilePhase(
//     ::ConstLowering,
//     name = "Const1",
//     description = "Substitute calls to const properties with constant values"
//   )

// internal val constPhase2 =
//   makeIrFilePhase(
//     ::ConstLowering,
//     name = "Const2",
//     description = "Substitute calls to const properties with constant values"
//   )
// END OF MODIFICATIONS

// TODO(b/317551802): Try to remove this pass when we switch to Kotlin 2.0.
class ConstLowering(val context: JvmBackendContext) : FileLoweringPass {
  val inlineConstTracker = context.state.configuration[CommonConfigurationKeys.INLINE_CONST_TRACKER]

  override fun lower(irFile: IrFile) =
    irFile.transformChildrenVoid(JvmInlineConstTransformer(irFile, inlineConstTracker))
}

private class JvmInlineConstTransformer(
  val irFile: IrFile,
  val inlineConstTracker: InlineConstTracker?,
) : InlineConstTransformer() {
  override val IrField.constantInitializer
    get() = constantValue()

  override fun reportInlineConst(field: IrField, value: IrConst) {
    if (inlineConstTracker == null) return
    if (field.origin != IrDeclarationOrigin.IR_EXTERNAL_JAVA_DECLARATION_STUB) return

    val path = irFile.path
    val owner =
      field.parentAsClass.classId?.asString()?.replace(".", "$")?.replace("/", ".") ?: return
    val name = field.name.asString()
    val constType = value.kind.asString

    inlineConstTracker.report(path, owner, name, constType)
  }
}
