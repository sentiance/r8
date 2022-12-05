// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.desugar.varhandle;

import com.android.tools.r8.cf.code.CfCheckCast;
import com.android.tools.r8.cf.code.CfInstruction;
import com.android.tools.r8.cf.code.CfInvoke;
import com.android.tools.r8.cf.code.CfLoad;
import com.android.tools.r8.cf.code.CfNew;
import com.android.tools.r8.cf.code.CfStackInstruction;
import com.android.tools.r8.cf.code.CfStackInstruction.Opcode;
import com.android.tools.r8.cf.code.CfStore;
import com.android.tools.r8.contexts.CompilationContext.ClassSynthesisDesugaringContext;
import com.android.tools.r8.contexts.CompilationContext.MethodProcessingContext;
import com.android.tools.r8.errors.MissingGlobalSyntheticsConsumerDiagnostic;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.CfCode;
import com.android.tools.r8.graph.DexApplicationReadFlags;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramDefinition;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.desugar.CfClassSynthesizerDesugaring;
import com.android.tools.r8.ir.desugar.CfClassSynthesizerDesugaringEventConsumer;
import com.android.tools.r8.ir.desugar.CfInstructionDesugaring;
import com.android.tools.r8.ir.desugar.CfInstructionDesugaringCollection;
import com.android.tools.r8.ir.desugar.CfInstructionDesugaringEventConsumer;
import com.android.tools.r8.ir.desugar.DesugarDescription;
import com.android.tools.r8.ir.desugar.FreshLocalProvider;
import com.android.tools.r8.ir.desugar.LocalStackAllocator;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.objectweb.asm.Opcodes;

public class VarHandleDesugaring implements CfInstructionDesugaring, CfClassSynthesizerDesugaring {

  private final AppView<?> appView;
  private final DexItemFactory factory;

  public static VarHandleDesugaring create(AppView<?> appView) {
    return appView.options().shouldDesugarVarHandle() ? new VarHandleDesugaring(appView) : null;
  }

  public static void registerSynthesizedCodeReferences(DexItemFactory factory) {
    VarHandleDesugaringMethods.registerSynthesizedCodeReferences(factory);
    factory.createSynthesizedType(DexItemFactory.desugarMethodHandlesLookupDescriptorString);
  }

  public VarHandleDesugaring(AppView<?> appView) {
    this.appView = appView;
    this.factory = appView.dexItemFactory();
  }

  @Override
  public void scan(
      ProgramMethod programMethod, CfInstructionDesugaringEventConsumer eventConsumer) {
    if (programMethod.getHolderType() == factory.desugarVarHandleType) {
      return;
    }
    CfCode cfCode = programMethod.getDefinition().getCode().asCfCode();
    for (CfInstruction instruction : cfCode.getInstructions()) {
      scanInstruction(instruction, eventConsumer, programMethod);
    }
  }

  private void scanInstruction(
      CfInstruction instruction,
      CfInstructionDesugaringEventConsumer eventConsumer,
      ProgramMethod context) {
    assert !instruction.isInitClass();
    if (instruction.isInvoke()) {
      CfInvoke cfInvoke = instruction.asInvoke();
      if (refersToVarHandle(cfInvoke.getMethod(), factory)) {
        ensureVarHandleClass(eventConsumer, context);
      }
      if (refersToMethodHandlesLookup(cfInvoke.getMethod(), factory)) {
        ensureMethodHandlesLookupClass(eventConsumer, context);
      }
      return;
    }
  }

  private static boolean refersToVarHandle(DexType type, DexItemFactory factory) {
    if (type == factory.varHandleType) {
      // All references to java.lang.invoke.VarHandle is rewritten during application reading.
      assert false;
      return true;
    }
    return type == factory.desugarVarHandleType;
  }

  private static boolean refersToVarHandle(DexType[] types, DexItemFactory factory) {
    for (DexType type : types) {
      if (refersToVarHandle(type, factory)) {
        return true;
      }
    }
    return false;
  }

  public static boolean refersToVarHandle(DexMethod method, DexItemFactory factory) {
    if (refersToVarHandle(method.holder, factory)) {
      return true;
    }
    return refersToVarHandle(method.proto, factory);
  }

  private static boolean refersToVarHandle(DexProto proto, DexItemFactory factory) {
    if (refersToVarHandle(proto.returnType, factory)) {
      return true;
    }
    return refersToVarHandle(proto.parameters.values, factory);
  }

  public static boolean refersToVarHandle(DexField field, DexItemFactory factory) {
    if (refersToVarHandle(field.holder, factory)) {
      assert false : "The VarHandle class has no fields.";
      return true;
    }
    return refersToVarHandle(field.type, factory);
  }

  private static boolean refersToMethodHandlesLookup(DexType type, DexItemFactory factory) {
    if (type == factory.methodHandlesLookupType) {
      // All references to java.lang.invoke.MethodHandles$Lookup is rewritten during application
      // reading.
      assert false;
      return true;
    }
    return type == factory.desugarMethodHandlesLookupType;
  }

  private static boolean refersToMethodHandlesLookup(DexType[] types, DexItemFactory factory) {
    for (DexType type : types) {
      if (refersToMethodHandlesLookup(type, factory)) {
        return true;
      }
    }
    return false;
  }

  public static boolean refersToMethodHandlesLookup(DexMethod method, DexItemFactory factory) {
    if (refersToMethodHandlesLookup(method.holder, factory)) {
      return true;
    }
    return refersToMethodHandlesLookup(method.proto, factory);
  }

  private static boolean refersToMethodHandlesLookup(DexProto proto, DexItemFactory factory) {
    if (refersToMethodHandlesLookup(proto.returnType, factory)) {
      return true;
    }
    return refersToMethodHandlesLookup(proto.parameters.values, factory);
  }

  public static boolean refersToMethodHandlesLookup(DexField field, DexItemFactory factory) {
    if (refersToMethodHandlesLookup(field.holder, factory)) {
      assert false : "The MethodHandles$Lookup class has no fields.";
      return true;
    }
    return refersToMethodHandlesLookup(field.type, factory);
  }

  private void ensureMethodHandlesLookupClass(
      VarHandleDesugaringEventConsumer eventConsumer, Collection<ProgramDefinition> contexts) {
    appView
        .getSyntheticItems()
        .ensureGlobalClass(
            () -> new MissingGlobalSyntheticsConsumerDiagnostic("VarHandle desugaring"),
            kinds -> kinds.METHOD_HANDLES_LOOKUP,
            factory.desugarMethodHandlesLookupType,
            contexts,
            appView,
            builder ->
                VarHandleDesugaringMethods.generateDesugarMethodHandlesLookupClass(
                    builder, appView.dexItemFactory()),
            eventConsumer::acceptVarHandleDesugaringClass);
  }

  private void ensureMethodHandlesLookupClass(
      VarHandleDesugaringEventConsumer eventConsumer, ProgramDefinition context) {
    ensureMethodHandlesLookupClass(eventConsumer, ImmutableList.of(context));
  }

  private void ensureVarHandleClass(
      VarHandleDesugaringEventConsumer eventConsumer, Collection<ProgramDefinition> contexts) {
    appView
        .getSyntheticItems()
        .ensureGlobalClass(
            () -> new MissingGlobalSyntheticsConsumerDiagnostic("VarHandle desugaring"),
            kinds -> kinds.VAR_HANDLE,
            factory.desugarVarHandleType,
            contexts,
            appView,
            builder ->
                VarHandleDesugaringMethods.generateDesugarVarHandleClass(
                    builder, appView.dexItemFactory()),
            eventConsumer::acceptVarHandleDesugaringClass);
  }

  private void ensureVarHandleClass(
      VarHandleDesugaringEventConsumer eventConsumer, ProgramDefinition context) {
    ensureVarHandleClass(eventConsumer, ImmutableList.of(context));
  }

  @Override
  public boolean needsDesugaring(CfInstruction instruction, ProgramMethod context) {
    return computeDescription(instruction, context).needsDesugaring();
  }

  @Override
  public Collection<CfInstruction> desugarInstruction(
      CfInstruction instruction,
      FreshLocalProvider freshLocalProvider,
      LocalStackAllocator localStackAllocator,
      CfInstructionDesugaringEventConsumer eventConsumer,
      ProgramMethod context,
      MethodProcessingContext methodProcessingContext,
      CfInstructionDesugaringCollection desugaringCollection,
      DexItemFactory dexItemFactory) {
    return computeDescription(instruction, context)
        .desugarInstruction(
            freshLocalProvider,
            localStackAllocator,
            eventConsumer,
            context,
            methodProcessingContext,
            dexItemFactory);
  }

  private DesugarDescription computeDescription(CfInstruction instruction, ProgramMethod context) {
    if (!instruction.isInvoke()) {
      return DesugarDescription.nothing();
    }
    CfInvoke invoke = instruction.asInvoke();
    DexType holder = invoke.getMethod().getHolderType();
    if (holder != factory.methodHandlesType
        && holder != factory.methodHandlesLookupType
        && holder != factory.desugarVarHandleType) {
      return DesugarDescription.nothing();
    }
    DexMethod method = invoke.getMethod();
    if (method.getHolderType() == factory.methodHandlesType) {
      if (method.getName().equals(factory.createString("lookup"))
          && method.getReturnType() == factory.desugarMethodHandlesLookupType
          && method.getArity() == 0
          && invoke.isInvokeStatic()) {
        return computeMethodHandlesLookup(factory);
      } else {
        return DesugarDescription.nothing();
      }
    }

    if (method.getHolderType() == factory.methodHandlesLookupType) {
      assert invoke.isInvokeVirtual();

      if (invoke.getMethod().getReturnType().equals(factory.desugarVarHandleType)) {
        return computeInvokeMethodHandleLookupMethodReturningVarHandle(factory, invoke);
      } else {
        assert invoke.getMethod().getReturnType().equals(factory.methodHandleType);
        return computeInvokeMethodHandleLookupMethodReturningMethodHandle(factory, invoke);
      }
    }

    if (method.getHolderType() == factory.desugarVarHandleType) {
      assert invoke.isInvokeVirtual();
      DexString name = method.getName();
      int arity = method.getProto().getArity();
      // TODO(b/247076137): Support two coordinates (array element VarHandle).
      if (name.equals(factory.compareAndSetString)) {
        assert arity == 3;
        return computeDesugarSignaturePolymorphicMethod(invoke, arity - 2);
      } else if (name.equals(factory.getString)) {
        assert arity == 1;
        return computeDesugarSignaturePolymorphicMethod(invoke, arity);
      } else if (name.equals(factory.setString)) {
        assert arity == 2;
        return computeDesugarSignaturePolymorphicMethod(invoke, arity - 1);
      } else {
        // TODO(b/247076137): Insert runtime exception - unsupported VarHandle operation.
        return DesugarDescription.nothing();
      }
    }

    return DesugarDescription.nothing();
  }

  public DesugarDescription computeMethodHandlesLookup(DexItemFactory factory) {
    return DesugarDescription.builder()
        .setDesugarRewrite(
            (freshLocalProvider,
                localStackAllocator,
                eventConsumer,
                context,
                methodProcessingContext,
                dexItemFactory) ->
                ImmutableList.of(
                    new CfNew(factory.desugarMethodHandlesLookupType),
                    new CfStackInstruction(Opcode.Dup),
                    new CfInvoke(
                        Opcodes.INVOKESPECIAL,
                        factory.createMethod(
                            factory.desugarMethodHandlesLookupType,
                            factory.createProto(factory.voidType),
                            factory.constructorMethodName),
                        false)))
        .build();
  }

  public DesugarDescription computeInvokeMethodHandleLookupMethodReturningVarHandle(
      DexItemFactory factory, CfInvoke invoke) {
    return DesugarDescription.builder()
        .setDesugarRewrite(
            (freshLocalProvider,
                localStackAllocator,
                eventConsumer,
                context,
                methodProcessingContext,
                dexItemFactory) ->
                ImmutableList.of(
                    new CfInvoke(
                        Opcodes.INVOKEVIRTUAL,
                        factory.createMethod(
                            factory.desugarMethodHandlesLookupType,
                            factory.createProto(
                                factory.desugarVarHandleType,
                                invoke.getMethod().getProto().getParameters()),
                            invoke.getMethod().getName()),
                        false)))
        .build();
  }

  public DesugarDescription computeInvokeMethodHandleLookupMethodReturningMethodHandle(
      DexItemFactory factory, CfInvoke invoke) {
    return DesugarDescription.builder()
        .setDesugarRewrite(
            (freshLocalProvider,
                localStackAllocator,
                eventConsumer,
                context,
                methodProcessingContext,
                dexItemFactory) ->
                ImmutableList.of(
                    new CfInvoke(
                        Opcodes.INVOKEVIRTUAL,
                        factory.createMethod(
                            factory.desugarMethodHandlesLookupType,
                            invoke.getMethod().getProto(),
                            invoke.getMethod().getName()),
                        false)))
        .build();
  }

  public DesugarDescription computeDesugarSignaturePolymorphicMethod(
      CfInvoke invoke, int coordinates) {
    return DesugarDescription.builder()
        .setDesugarRewrite(
            (freshLocalProvider,
                localStackAllocator,
                eventConsumer,
                context,
                methodProcessingContext,
                dexItemFactory) ->
                desugarSignaturePolymorphicMethod(invoke, coordinates, freshLocalProvider))
        .build();
  }

  private boolean isPrimitiveThatIsNotBoxed(DexType type) {
    return type.isIntType() || type.isLongType();
  }

  private DexType objectOrPrimitiveReturnType(DexType type) {
    return type.isPrimitiveType() || type.isVoidType() ? type : factory.objectType;
  }

  private DexType objectOrPrimitiveParameterType(DexType type) {
    return isPrimitiveThatIsNotBoxed(type) || type.isVoidType() ? type : factory.objectType;
  }

  private Collection<CfInstruction> desugarSignaturePolymorphicMethod(
      CfInvoke invoke, int coordinates, FreshLocalProvider freshLocalProvider) {
    assert invoke.isInvokeVirtual();
    // TODO(b/247076137): Support two coordinates (array element VarHandle).
    assert coordinates == 1 && invoke.getMethod().getProto().getArity() >= coordinates;
    // Only support zero, one and two arguments after coordinates.
    int nonCoordinateArguments = invoke.getMethod().getProto().getArity() - coordinates;
    assert nonCoordinateArguments <= 2;

    DexProto proto = invoke.getMethod().getProto();
    DexType ct1Type = invoke.getMethod().getProto().getParameter(0);
    if (!ct1Type.isClassType()) {
      return null;
    }

    // Convert the arguments by boxing except for primitive int and long.
    ImmutableList.Builder<CfInstruction> builder = ImmutableList.builder();
    List<DexType> newParameters = new ArrayList<>(proto.parameters.size());
    newParameters.add(factory.objectType);
    if (nonCoordinateArguments > 0) {
      DexType argumentType = objectOrPrimitiveParameterType(proto.parameters.get(coordinates));
      boolean hasWideArgument = false;
      for (int i = coordinates; i < proto.parameters.size(); i++) {
        hasWideArgument = hasWideArgument || proto.parameters.get(i).isWideType();
        DexType type = objectOrPrimitiveParameterType(proto.parameters.get(i));
        if (type != argumentType) {
          argumentType = factory.objectType;
        }
      }
      assert isPrimitiveThatIsNotBoxed(argumentType) || argumentType == factory.objectType;
      // Ensure all arguments are boxed.
      for (int i = coordinates; i < proto.parameters.size(); i++) {
        if (argumentType.isPrimitiveType()) {
          newParameters.add(argumentType);
        } else {
          boolean lastArgument = i == proto.parameters.size() - 1;
          // Pass all boxed objects as Object.
          newParameters.add(factory.objectType);
          if (!proto.parameters.get(i).isPrimitiveType()) {
            continue;
          }
          int local = -1;
          // For boxing of the second to last argument (we only have one or two) bring it to TOS.
          if (!lastArgument) {
            if (hasWideArgument) {
              local = freshLocalProvider.getFreshLocal(2);
              builder.add(new CfStore(ValueType.fromDexType(proto.parameters.get(i + 1)), local));
            } else {
              builder.add(new CfStackInstruction(Opcode.Swap));
            }
          }
          builder.add(
              new CfInvoke(
                  Opcodes.INVOKESTATIC,
                  factory.getBoxPrimitiveMethod(proto.parameters.get(i)),
                  false));
          // When boxing of the second to last argument (we only have one or two) bring last
          // argument back to TOS.
          if (!lastArgument) {
            if (hasWideArgument) {
              assert local != -1;
              builder.add(new CfLoad(ValueType.fromDexType(proto.parameters.get(i + 1)), local));
            } else {
              builder.add(new CfStackInstruction(Opcode.Swap));
            }
          }
        }
      }
    }
    assert newParameters.size() == proto.parameters.size();
    // TODO(b/247076137): Also convert return type if reference type and not Object?.
    DexProto newProto =
        factory.createProto(objectOrPrimitiveReturnType(proto.returnType), newParameters);
    DexMethod newMethod =
        factory.createMethod(factory.desugarVarHandleType, newProto, invoke.getMethod().getName());
    builder.add(new CfInvoke(Opcodes.INVOKEVIRTUAL, newMethod, false));
    if (proto.returnType.isClassType()
        && proto.returnType != factory.objectType
        && proto.returnType != factory.voidType) {
      builder.add(new CfCheckCast(proto.returnType));
    }
    return builder.build();
  }

  @Override
  public String uniqueIdentifier() {
    return "$varhandle";
  }

  @Override
  // TODO(b/247076137): Is synthesizeClasses needed? Can DesugarVarHandle be created during
  //  desugaring instead?
  public void synthesizeClasses(
      ClassSynthesisDesugaringContext processingContext,
      CfClassSynthesizerDesugaringEventConsumer eventConsumer) {
    DexApplicationReadFlags flags = appView.appInfo().app().getFlags();
    if (flags.hasReadVarHandleReferenceFromProgramClass()) {
      List<ProgramDefinition> classes = new ArrayList<>();
      for (DexType varHandleWitness : flags.getVarHandleWitnesses()) {
        DexClass dexClass = appView.contextIndependentDefinitionFor(varHandleWitness);
        assert dexClass != null;
        assert dexClass.isProgramClass();
        classes.add(dexClass.asProgramClass());
      }
      ensureVarHandleClass(eventConsumer, classes);
    }
  }
}
