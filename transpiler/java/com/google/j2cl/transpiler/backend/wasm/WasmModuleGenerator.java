/*
 * Copyright 2020 Google Inc.
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
package com.google.j2cl.transpiler.backend.wasm;

import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.collect.ImmutableSet;
import com.google.j2cl.common.OutputUtils;
import com.google.j2cl.common.Problems;
import com.google.j2cl.transpiler.ast.AbstractVisitor;
import com.google.j2cl.transpiler.ast.CompilationUnit;
import com.google.j2cl.transpiler.ast.DeclaredTypeDescriptor;
import com.google.j2cl.transpiler.ast.Field;
import com.google.j2cl.transpiler.ast.Method;
import com.google.j2cl.transpiler.ast.MethodDescriptor;
import com.google.j2cl.transpiler.ast.Type;
import com.google.j2cl.transpiler.ast.TypeDeclaration;
import com.google.j2cl.transpiler.ast.TypeDescriptor;
import com.google.j2cl.transpiler.ast.TypeDescriptors;
import com.google.j2cl.transpiler.ast.Variable;
import com.google.j2cl.transpiler.backend.common.SourceBuilder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Generates a WASM module containing all the code for the application. */
public class WasmModuleGenerator {
  private final Problems problems;
  private final Path outputPath;
  private final Set<String> pendingEntryPoints;
  private final SourceBuilder builder = new SourceBuilder();
  private GenerationEnvironment environment;
  /**
   * Maps type declarations to the corresponding type objects to allow access to the implementations
   * of super classes.
   */
  private Map<TypeDeclaration, Type> typesByTypeDeclaration;

  public WasmModuleGenerator(Path outputPath, ImmutableSet<String> entryPoints, Problems problems) {
    this.outputPath = outputPath;
    this.pendingEntryPoints = new HashSet<>(entryPoints);
    this.problems = problems;
  }

  public void generateOutputs(List<CompilationUnit> compilationUnits) {
    environment = new GenerationEnvironment(compilationUnits);
    builder.append(";;; Code generated by J2WASM");
    // Collect the implementations for all types
    typesByTypeDeclaration =
        compilationUnits.stream()
            .flatMap(cu -> cu.getTypes().stream())
            .collect(toImmutableMap(Type::getDeclaration, Function.identity()));
    emitRttHierarchy(compilationUnits);
    emitTypes(compilationUnits);
    OutputUtils.writeToFile(outputPath.resolve("module.wat"), builder.build(), problems);
    if (!pendingEntryPoints.isEmpty()) {
      problems.error("Entry points %s not found.", pendingEntryPoints);
    }
  }

  private void emitTypes(List<CompilationUnit> compilationUnits) {
    for (CompilationUnit j2clCompilationUnit : compilationUnits) {
      builder.newLine();
      builder.append(
          ";;; Code for "
              + j2clCompilationUnit.getPackageName()
              + "."
              + j2clCompilationUnit.getName());
      for (Type type : j2clCompilationUnit.getTypes()) {
        renderType(type);
      }
    }
  }

  /** Emits the rtt hierarchy by assigning a global to each rtt. */
  private void emitRttHierarchy(List<CompilationUnit> compilationUnits) {
    // TODO(b/174715079): Consider tagging or emitting together with the rest of the type
    // to make the rtts show in the readables.
    Set<TypeDeclaration> emittedRtts = new HashSet<>();
    compilationUnits.stream()
        .flatMap(c -> c.getTypes().stream())
        .filter(not(Type::isInterface)) // Interfaces do not have rtts.
        .forEach(t -> emitRttGlobal(t.getDeclaration(), emittedRtts));
  }

  private void emitRttGlobal(TypeDeclaration typeDeclaration, Set<TypeDeclaration> emittedRtts) {
    if (!emittedRtts.add(typeDeclaration)) {
      return;
    }
    DeclaredTypeDescriptor superTypeDescriptor = typeDeclaration.getSuperTypeDescriptor();
    if (superTypeDescriptor != null) {
      // Supertype rtt needs to be emitted before the subtype since globals can only refer to
      // globals that are initialized before.
      emitRttGlobal(superTypeDescriptor.getTypeDeclaration(), emittedRtts);
    }
    int depth = typeDeclaration.getClassHierarchyDepth();
    String wasmTypeName = environment.getWasmTypeName(typeDeclaration);
    String superTypeRtt =
        superTypeDescriptor == null
            ? "(rtt.canon " + wasmTypeName + ")"
            : String.format(
                "(rtt.sub %s (global.get %s))",
                wasmTypeName,
                environment.getRttGlobalName(superTypeDescriptor.getTypeDeclaration()));
    builder.newLine();
    builder.append(
        String.format(
            "(global %s (rtt %d %s) %s)",
            environment.getRttGlobalName(typeDeclaration), depth, wasmTypeName, superTypeRtt));
  }

  private void renderType(Type type) {
    builder.newLine();
    builder.newLine();
    builder.append(";;; " + type.getKind() + "  " + type.getReadableDescription());
    renderStaticFields(type);
    renderTypeStruct(type);
    renderTypeMethods(type);
  }

  private void renderStaticFields(Type type) {
    builder.newLine();
    for (Field field : type.getStaticFields()) {
      builder.newLine();
      builder.append(
          "(global "
              + environment.getFieldName(field)
              + " "
              + environment.getWasmType(field.getDescriptor().getTypeDescriptor())
              + " ");
      ExpressionTranspiler.render(
          field.getDescriptor().getTypeDescriptor().getDefaultValue(), builder, environment);
      builder.append(")");
    }
  }

  private void renderTypeMethods(Type type) {
    for (Method method : type.getMethods()) {
      renderMethod(method);
    }
  }

  private void renderMethod(Method method) {
    builder.newLine();
    builder.newLine();
    builder.append(";;; " + method.getReadableDescription());
    builder.newLine();
    builder.append("(func " + environment.getMethodImplementationName(method.getDescriptor()));

    MethodDescriptor methodDescriptor = method.getDescriptor();
    if (pendingEntryPoints.remove(method.getQualifiedBinaryName())) {
      if (!method.isStatic()) {
        problems.error("Entry point [%s] is not a static method.", method.getQualifiedBinaryName());
      }
      builder.append(" (export \"" + methodDescriptor.getName() + "\")");
    }

    // Emit parameters
    builder.indent();
    if (!method.isStatic()) {
      // Add the implicit "this" parameter to instance methods and constructors.
      // Note that constructors and private methods can declare the parameter type to be the
      // enclosing type because they are not overridden but normal instance methods have to
      // declare the parameter more generically as java.lang.Object, since all the overrides need
      // to have matching signatures.
      // TODO(rluble): revisit once the wasm gc spec and implementation have function subtyping.
      builder.newLine();
      builder.append(
          "(param $this"
              + " "
              + environment.getWasmType(
                  (method.isConstructor() || methodDescriptor.getVisibility().isPrivate())
                      ? methodDescriptor.getEnclosingTypeDescriptor()
                      : TypeDescriptors.get().javaLangObject)
              + ")");
    }
    for (Variable parameter : method.getParameters()) {
      builder.newLine();
      builder.append(
          "(param "
              + environment.getDeclarationName(parameter)
              + " "
              + environment.getWasmType(parameter.getTypeDescriptor())
              + ")");
    }
    // Emit return type
    TypeDescriptor returnTypeDescriptor =
        method.isConstructor()
            ? methodDescriptor.getEnclosingTypeDescriptor()
            : methodDescriptor.getReturnTypeDescriptor();
    if (!TypeDescriptors.isPrimitiveVoid(returnTypeDescriptor)) {
      builder.newLine();
      builder.append("(result " + environment.getWasmType(returnTypeDescriptor) + ")");
    }
    // Emit locals.
    for (Variable variable : collectLocals(method)) {
      builder.newLine();
      builder.append(
          "(local "
              + environment.getDeclarationName(variable)
              + " "
              + environment.getWasmType(variable.getTypeDescriptor())
              + ")");
    }
    builder.newLine();
    new StatementTranspiler(builder, environment).renderStatement(method.getBody());
    if (method.isConstructor()) {
      // TODO(rluble): Add a pass to transform constructors into static methods.
      builder.newLine();
      builder.append("(local.get $this)");
    } else if (!TypeDescriptors.isPrimitiveVoid(method.getDescriptor().getReturnTypeDescriptor())) {
      // TODO(rluble): remove the dummy return value to keep WASM happy until the return statement
      // is properly implemented.
      builder.newLine();
      ExpressionTranspiler.render(
          method.getDescriptor().getReturnTypeDescriptor().getDefaultValue(), builder, environment);
    }
    builder.unindent();
    builder.newLine();
    builder.append(")");
  }

  private static List<Variable> collectLocals(Method method) {
    List<Variable> locals = new ArrayList<>();
    method
        .getBody()
        .accept(
            new AbstractVisitor() {
              @Override
              public void exitVariable(Variable variable) {
                locals.add(variable);
              }
            });
    return locals;
  }

  private void renderTypeStruct(Type type) {
    builder.newLine();
    builder.append("(type " + environment.getWasmTypeName(type.getTypeDescriptor()) + " (struct");
    builder.indent();
    renderTypeFields(type);
    builder.unindent();
    builder.newLine();
    builder.append("))");
  }

  private void renderTypeFields(Type type) {
    DeclaredTypeDescriptor superTypeDescriptor = type.getSuperTypeDescriptor();
    if (superTypeDescriptor != null) {
      Type supertype = typesByTypeDeclaration.get(superTypeDescriptor.getTypeDeclaration());
      if (supertype == null) {
        builder.newLine();
        builder.append(";; Missing supertype " + superTypeDescriptor.getReadableDescription());
      } else {
        renderTypeFields(typesByTypeDeclaration.get(superTypeDescriptor.getTypeDeclaration()));
      }
    }
    for (Field field : type.getFields()) {
      if (field.isStatic()) {
        continue;
      }
      builder.newLine();
      builder.append(
          "(field "
              + environment.getFieldName(field)
              + " "
              + environment.getWasmType(field.getDescriptor().getTypeDescriptor())
              + ")");
    }
  }
}
