/*
 * Copyright 2022 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.backend.kotlin

import com.google.j2cl.transpiler.ast.ArrayTypeDescriptor
import com.google.j2cl.transpiler.ast.CompilationUnit
import com.google.j2cl.transpiler.ast.DeclaredTypeDescriptor
import com.google.j2cl.transpiler.ast.Field
import com.google.j2cl.transpiler.ast.FieldDescriptor
import com.google.j2cl.transpiler.ast.MemberDescriptor
import com.google.j2cl.transpiler.ast.PrimitiveTypeDescriptor
import com.google.j2cl.transpiler.ast.PrimitiveTypes
import com.google.j2cl.transpiler.ast.Type
import com.google.j2cl.transpiler.ast.TypeDeclaration
import com.google.j2cl.transpiler.ast.TypeDescriptor
import com.google.j2cl.transpiler.ast.Visibility
import com.google.j2cl.transpiler.backend.kotlin.ast.Member

/** A set of local names used in this type. */
internal val Type.localNamesSet: Set<String>
  get() =
    ktMembers
      .mapNotNull { member ->
        when (member) {
          is Member.WithCompanionObject -> null
          is Member.WithJavaMember -> (member.javaMember as? Field)?.descriptor?.ktName
          is Member.WithType -> member.type.declaration.ktSimpleName
        }
      }
      .toSet()

/** A set of top-level qualified name strings in this compilation unit. */
internal val CompilationUnit.topLevelQualifiedNamesSet: Set<String>
  get() = types.map { it.declaration }.filter { !it.isKtNative }.map { it.ktQualifiedName }.toSet()

/** Kotlin mangled name for this member descriptor. */
internal val MemberDescriptor.ktMangledName: String
  get() = ktName + ktNameSuffix

/** Kotlin name suffix for this member descriptor. */
private val MemberDescriptor.ktNameSuffix: String
  get() =
    when (visibility!!) {
      Visibility.PUBLIC -> ktPropertyNameSuffix
      Visibility.PROTECTED -> ktPropertyNameSuffix
      Visibility.PACKAGE_PRIVATE ->
        "_pp_${enclosingTypeDescriptor.typeDeclaration.packageName?.replace('.', '_') ?: ""}"
      Visibility.PRIVATE ->
        "_private_${enclosingTypeDescriptor.typeDeclaration.privateMemberSuffix}"
    }

/** Kotlin property name suffix for this member descriptor. */
private val MemberDescriptor.ktPropertyNameSuffix: String
  get() = if (this is FieldDescriptor && hasConflictingKtProperty) "_ktPropertyConflict" else ""

/** Whether this field descriptor has property with conflicting name in Kotlin. */
private val FieldDescriptor.hasConflictingKtProperty: Boolean
  get() = enclosingTypeDescriptor.polymorphicMethods.any { it.isKtProperty && it.ktName == ktName }

/** A suffix for private members in this type declaration. */
private val TypeDeclaration.privateMemberSuffix: String
  get() = if (isInterface) mangledName else "$classHierarchyDepth"

/** Original qualified name of this type declaration. */
private val TypeDeclaration.originalQualifiedName: String
  get() = if (isLocal) originalSimpleSourceName!! else qualifiedSourceName

/** Kotlin qualified name for this type declaration. */
internal val TypeDeclaration.ktQualifiedName: String
  get() = ktNativeQualifiedName ?: originalQualifiedName

/** Kotlin qualified name for this type declaration when used as a super-type. */
internal val TypeDeclaration.ktQualifiedNameAsSuperType: String
  get() = ktBridgeQualifiedName ?: ktQualifiedName

/** Kotlin simple name for this type declaration. */
internal val TypeDeclaration.ktSimpleName: String
  get() = ktQualifiedName.qualifiedNameToSimpleName()

/**
 * Kotlin qualified name for this type declaration.
 *
 * @param asSuperType whether to use bridge name for super-type if present.
 */
internal fun TypeDeclaration.ktQualifiedName(asSuperType: Boolean = false) =
  if (asSuperType) ktQualifiedNameAsSuperType else ktQualifiedName

/**
 * Kotlin simple name for this type declaration.
 *
 * @param asSuperType whether to use bridge name for super-type if present.
 */
internal fun TypeDeclaration.ktSimpleName(asSuperType: Boolean = false) =
  ktQualifiedName(asSuperType = asSuperType).qualifiedNameToSimpleName()

/** Kotlin qualified name for this type declaration. */
internal val TypeDescriptor.ktQualifiedName: String
  get() =
    when (this) {
      is PrimitiveTypeDescriptor -> toBoxedType().ktQualifiedName
      is ArrayTypeDescriptor ->
        when (componentTypeDescriptor!!) {
          PrimitiveTypes.BOOLEAN -> "kotlin.BooleanArray"
          PrimitiveTypes.CHAR -> "kotlin.CharArray"
          PrimitiveTypes.BYTE -> "kotlin.ByteArray"
          PrimitiveTypes.SHORT -> "kotlin.ShortArray"
          PrimitiveTypes.INT -> "kotlin.IntArray"
          PrimitiveTypes.LONG -> "kotlin.LongArray"
          PrimitiveTypes.FLOAT -> "kotlin.FloatArray"
          PrimitiveTypes.DOUBLE -> "kotlin.DoubleArray"
          else -> "kotlin.Array"
        }
      is DeclaredTypeDescriptor -> typeDeclaration.ktQualifiedName
      else -> null
    } ?: error("$this.ktQualifiedName()")

/** A list of components for this qualified name string. */
internal fun String.qualifiedNameComponents(): List<String> = split(".")

/** Simple name for this qualified name string. */
internal fun String.qualifiedNameToSimpleName(): String = qualifiedNameComponents().last()

/** Alias for this qualified name string. */
internal fun String.qualifiedNameToAlias(): String = qualifiedNameComponents().joinToString("_")
