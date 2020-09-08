// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.enums;

import static com.android.tools.r8.ir.analysis.type.Nullability.definitelyNotNull;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.CfCode;
import com.android.tools.r8.graph.ClassAccessFlags;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexApplication.Builder;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.EnumValueInfoMapCollection;
import com.android.tools.r8.graph.EnumValueInfoMapCollection.EnumValueInfo;
import com.android.tools.r8.graph.EnumValueInfoMapCollection.EnumValueInfoMap;
import com.android.tools.r8.graph.FieldAccessFlags;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.graph.ParameterAnnotationsList;
import com.android.tools.r8.ir.analysis.type.ArrayTypeElement;
import com.android.tools.r8.ir.analysis.type.Nullability;
import com.android.tools.r8.ir.analysis.type.TypeElement;
import com.android.tools.r8.ir.analysis.value.AbstractValue;
import com.android.tools.r8.ir.code.ArrayAccess;
import com.android.tools.r8.ir.code.ConstNumber;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.InstanceGet;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.android.tools.r8.ir.code.InvokeStatic;
import com.android.tools.r8.ir.code.MemberType;
import com.android.tools.r8.ir.code.Phi;
import com.android.tools.r8.ir.code.StaticGet;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.conversion.IRConverter;
import com.android.tools.r8.ir.optimize.enums.EnumInstanceFieldData.EnumInstanceFieldKnownData;
import com.android.tools.r8.ir.synthetic.EnumUnboxingCfCodeProvider;
import com.android.tools.r8.origin.SynthesizedOrigin;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

public class EnumUnboxingRewriter {

  public static final String ENUM_UNBOXING_UTILITY_CLASS_NAME = "$r8$EnumUnboxingUtility";
  public static final String ENUM_UNBOXING_UTILITY_METHOD_PREFIX = "$enumboxing$";
  private static final int REQUIRED_CLASS_FILE_VERSION = 52;

  private final AppView<AppInfoWithLiveness> appView;
  private final DexItemFactory factory;
  private final EnumValueInfoMapCollection enumsToUnbox;
  private final EnumInstanceFieldDataMap unboxedEnumsInstanceFieldData;
  private final Map<DexMethod, DexEncodedMethod> utilityMethods = new ConcurrentHashMap<>();
  private final Map<DexField, DexEncodedField> extraUtilityFields = new ConcurrentHashMap<>();

  private final DexMethod ordinalUtilityMethod;
  private final DexMethod equalsUtilityMethod;
  private final DexMethod compareToUtilityMethod;
  private final DexMethod valuesUtilityMethod;
  private final DexMethod zeroCheckMethod;
  private final DexMethod zeroCheckMessageMethod;

  EnumUnboxingRewriter(
      AppView<AppInfoWithLiveness> appView,
      Set<DexType> enumsToUnbox,
      EnumInstanceFieldDataMap unboxedEnumsInstanceFieldData) {
    this.appView = appView;
    this.factory = appView.dexItemFactory();
    EnumValueInfoMapCollection.Builder builder = EnumValueInfoMapCollection.builder();
    for (DexType toUnbox : enumsToUnbox) {
      assert appView.appInfo().withLiveness().getEnumValueInfoMap(toUnbox) != null;
      builder.put(toUnbox, appView.appInfo().withLiveness().getEnumValueInfoMap(toUnbox));
    }
    this.enumsToUnbox = builder.build();
    this.unboxedEnumsInstanceFieldData = unboxedEnumsInstanceFieldData;

    // Custom methods for java.lang.Enum methods ordinal, equals and compareTo.
    this.ordinalUtilityMethod =
        factory.createMethod(
            factory.enumUnboxingUtilityType,
            factory.createProto(factory.intType, factory.intType),
            ENUM_UNBOXING_UTILITY_METHOD_PREFIX + "ordinal");
    this.equalsUtilityMethod =
        factory.createMethod(
            factory.enumUnboxingUtilityType,
            factory.createProto(factory.booleanType, factory.intType, factory.intType),
            ENUM_UNBOXING_UTILITY_METHOD_PREFIX + "equals");
    this.compareToUtilityMethod =
        factory.createMethod(
            factory.enumUnboxingUtilityType,
            factory.createProto(factory.intType, factory.intType, factory.intType),
            ENUM_UNBOXING_UTILITY_METHOD_PREFIX + "compareTo");
    // Custom methods for generated field $VALUES initialization.
    this.valuesUtilityMethod =
        factory.createMethod(
            factory.enumUnboxingUtilityType,
            factory.createProto(factory.intArrayType, factory.intType),
            ENUM_UNBOXING_UTILITY_METHOD_PREFIX + "values");
    // Custom methods for Object#getClass without outValue and Objects.requireNonNull.
    this.zeroCheckMethod =
        factory.createMethod(
            factory.enumUnboxingUtilityType,
            factory.createProto(factory.voidType, factory.intType),
            ENUM_UNBOXING_UTILITY_METHOD_PREFIX + "zeroCheck");
    this.zeroCheckMessageMethod =
        factory.createMethod(
            factory.enumUnboxingUtilityType,
            factory.createProto(factory.voidType, factory.intType, factory.stringType),
            ENUM_UNBOXING_UTILITY_METHOD_PREFIX + "zeroCheckMessage");
  }

  public EnumValueInfoMapCollection getEnumsToUnbox() {
    return enumsToUnbox;
  }

  Set<Phi> rewriteCode(IRCode code) {
    // We should not process the enum methods, they will be removed and they may contain invalid
    // rewriting rules.
    if (enumsToUnbox.isEmpty()) {
      return Sets.newIdentityHashSet();
    }
    assert code.isConsistentSSABeforeTypesAreCorrect();
    Map<Instruction, DexType> convertedEnums = new IdentityHashMap<>();
    Set<Phi> affectedPhis = Sets.newIdentityHashSet();
    InstructionListIterator iterator = code.instructionListIterator();
    while (iterator.hasNext()) {
      Instruction instruction = iterator.next();
      // Rewrites specific enum methods, such as ordinal, into their corresponding enum unboxed
      // counterpart.
      if (instruction.isInvokeMethodWithReceiver()) {
        InvokeMethodWithReceiver invokeMethod = instruction.asInvokeMethodWithReceiver();
        DexMethod invokedMethod = invokeMethod.getInvokedMethod();
        DexType enumType = getEnumTypeOrNull(invokeMethod.getReceiver(), convertedEnums);
        if (enumType != null) {
          if (invokedMethod == factory.enumMembers.ordinalMethod
              || invokedMethod == factory.enumMembers.hashCode) {
            replaceEnumInvoke(
                iterator, invokeMethod, ordinalUtilityMethod, m -> synthesizeOrdinalMethod());
            continue;
          } else if (invokedMethod == factory.enumMembers.equals) {
            replaceEnumInvoke(
                iterator, invokeMethod, equalsUtilityMethod, m -> synthesizeEqualsMethod());
            continue;
          } else if (invokedMethod == factory.enumMembers.compareTo) {
            replaceEnumInvoke(
                iterator, invokeMethod, compareToUtilityMethod, m -> synthesizeCompareToMethod());
            continue;
          } else if (invokedMethod == factory.enumMembers.nameMethod
              || invokedMethod == factory.enumMembers.toString) {
            DexMethod toStringMethod =
                computeInstanceFieldUtilityMethod(enumType, factory.enumMembers.nameField);
            iterator.replaceCurrentInstruction(
                new InvokeStatic(
                    toStringMethod, invokeMethod.outValue(), invokeMethod.arguments()));
            continue;
          } else if (invokedMethod == factory.objectMembers.getClass) {
            assert !invokeMethod.hasOutValue() || !invokeMethod.outValue().hasAnyUsers();
            replaceEnumInvoke(
                iterator, invokeMethod, zeroCheckMethod, m -> synthesizeZeroCheckMethod());
          }
        }
        // TODO(b/147860220): rewrite also other enum methods.
      } else if (instruction.isInvokeStatic()) {
        InvokeStatic invokeStatic = instruction.asInvokeStatic();
        DexMethod invokedMethod = invokeStatic.getInvokedMethod();
        if (invokedMethod == factory.enumMembers.valueOf
            && invokeStatic.inValues().get(0).isConstClass()) {
          DexType enumType =
              invokeStatic.inValues().get(0).getConstInstruction().asConstClass().getValue();
          if (enumsToUnbox.containsEnum(enumType)) {
            DexMethod valueOfMethod = computeValueOfUtilityMethod(enumType);
            Value outValue = invokeStatic.outValue();
            Value rewrittenOutValue = null;
            if (outValue != null) {
              rewrittenOutValue = code.createValue(TypeElement.getInt());
              affectedPhis.addAll(outValue.uniquePhiUsers());
            }
            InvokeStatic invoke =
                new InvokeStatic(
                    valueOfMethod,
                    rewrittenOutValue,
                    Collections.singletonList(invokeStatic.inValues().get(1)));
            iterator.replaceCurrentInstruction(invoke);
            convertedEnums.put(invoke, enumType);
            continue;
          }
        } else if (invokedMethod == factory.javaLangSystemMethods.identityHashCode) {
          assert invokeStatic.arguments().size() == 1;
          Value argument = invokeStatic.getArgument(0);
          DexType enumType = getEnumTypeOrNull(argument, convertedEnums);
          if (enumType != null) {
            invokeStatic.outValue().replaceUsers(argument);
            iterator.removeOrReplaceByDebugLocalRead();
          }
        } else if (invokedMethod == factory.stringMembers.valueOf) {
          assert invokeStatic.arguments().size() == 1;
          Value argument = invokeStatic.getArgument(0);
          DexType enumType = getEnumTypeOrNull(argument, convertedEnums);
          if (enumType != null) {
            DexMethod stringValueOfMethod = computeStringValueOfUtilityMethod(enumType);
            iterator.replaceCurrentInstruction(
                new InvokeStatic(
                    stringValueOfMethod, invokeStatic.outValue(), invokeStatic.arguments()));
            continue;
          }
        } else if (invokedMethod == factory.objectsMethods.requireNonNull) {
          assert invokeStatic.arguments().size() == 1;
          Value argument = invokeStatic.getArgument(0);
          DexType enumType = getEnumTypeOrNull(argument, convertedEnums);
          if (enumType != null) {
            replaceEnumInvoke(
                iterator, invokeStatic, zeroCheckMethod, m -> synthesizeZeroCheckMethod());
          }
        } else if (invokedMethod == factory.objectsMethods.requireNonNullWithMessage) {
          assert invokeStatic.arguments().size() == 2;
          Value argument = invokeStatic.getArgument(0);
          DexType enumType = getEnumTypeOrNull(argument, convertedEnums);
          if (enumType != null) {
            replaceEnumInvoke(
                iterator,
                invokeStatic,
                zeroCheckMessageMethod,
                m -> synthesizeZeroCheckMessageMethod());
          }
        }
      }
      if (instruction.isStaticGet()) {
        StaticGet staticGet = instruction.asStaticGet();
        DexType holder = staticGet.getField().holder;
        if (enumsToUnbox.containsEnum(holder)) {
          if (staticGet.outValue() == null) {
            iterator.removeOrReplaceByDebugLocalRead();
            continue;
          }
          EnumValueInfoMap enumValueInfoMap = enumsToUnbox.getEnumValueInfoMap(holder);
          assert enumValueInfoMap != null;
          affectedPhis.addAll(staticGet.outValue().uniquePhiUsers());
          EnumValueInfo enumValueInfo = enumValueInfoMap.getEnumValueInfo(staticGet.getField());
          if (enumValueInfo == null && staticGet.getField().name == factory.enumValuesFieldName) {
            utilityMethods.computeIfAbsent(
                valuesUtilityMethod, m -> synthesizeValuesUtilityMethod());
            DexField fieldValues = createValuesField(holder);
            extraUtilityFields.computeIfAbsent(fieldValues, this::computeValuesEncodedField);
            DexMethod methodValues = createValuesMethod(holder);
            utilityMethods.computeIfAbsent(
                methodValues,
                m -> computeValuesEncodedMethod(m, fieldValues, enumValueInfoMap.size()));
            Value rewrittenOutValue =
                code.createValue(
                    ArrayTypeElement.create(TypeElement.getInt(), definitelyNotNull()));
            InvokeStatic invoke =
                new InvokeStatic(methodValues, rewrittenOutValue, ImmutableList.of());
            iterator.replaceCurrentInstruction(invoke);
            convertedEnums.put(invoke, holder);
          } else {
            // Replace by ordinal + 1 for null check (null is 0).
            assert enumValueInfo != null
                : "Invalid read to " + staticGet.getField().name + ", error during enum analysis";
            ConstNumber intConstant = code.createIntConstant(enumValueInfo.convertToInt());
            iterator.replaceCurrentInstruction(intConstant);
            convertedEnums.put(intConstant, holder);
          }
        }
      }

      if (instruction.isInstanceGet()) {
        InstanceGet instanceGet = instruction.asInstanceGet();
        DexType holder = instanceGet.getField().holder;
        if (enumsToUnbox.containsEnum(holder)) {
          DexMethod fieldMethod = computeInstanceFieldMethod(instanceGet.getField());
          Value rewrittenOutValue =
              code.createValue(
                  TypeElement.fromDexType(
                      fieldMethod.proto.returnType, Nullability.maybeNull(), appView));
          InvokeStatic invoke =
              new InvokeStatic(
                  fieldMethod, rewrittenOutValue, ImmutableList.of(instanceGet.object()));
          iterator.replaceCurrentInstruction(invoke);
          if (enumsToUnbox.containsEnum(instanceGet.getField().type)) {
            convertedEnums.put(invoke, instanceGet.getField().type);
          }
        }
      }

      // Rewrite array accesses from MyEnum[] (OBJECT) to int[] (INT).
      if (instruction.isArrayAccess()) {
        ArrayAccess arrayAccess = instruction.asArrayAccess();
        DexType enumType = getEnumTypeOrNull(arrayAccess);
        if (enumType != null) {
          instruction = arrayAccess.withMemberType(MemberType.INT);
          iterator.replaceCurrentInstruction(instruction);
          convertedEnums.put(instruction, enumType);
        }
        assert validateArrayAccess(arrayAccess);
      }
    }
    assert code.isConsistentSSABeforeTypesAreCorrect();
    return affectedPhis;
  }

  private DexMethod computeInstanceFieldMethod(DexField field) {
    EnumInstanceFieldKnownData enumFieldKnownData =
        unboxedEnumsInstanceFieldData.getInstanceFieldData(field.holder, field);
    if (enumFieldKnownData.isOrdinal()) {
      utilityMethods.computeIfAbsent(ordinalUtilityMethod, m -> synthesizeOrdinalMethod());
      return ordinalUtilityMethod;
    }
    return computeInstanceFieldUtilityMethod(field.holder, field);
  }

  private void replaceEnumInvoke(
      InstructionListIterator iterator,
      InvokeMethod invokeMethod,
      DexMethod method,
      Function<DexMethod, DexEncodedMethod> synthesizor) {
    utilityMethods.computeIfAbsent(method, synthesizor);
    Instruction instruction =
        new InvokeStatic(method, invokeMethod.outValue(), invokeMethod.arguments());
    iterator.replaceCurrentInstruction(instruction);
  }

  private boolean validateArrayAccess(ArrayAccess arrayAccess) {
    ArrayTypeElement arrayType = arrayAccess.array().getType().asArrayType();
    if (arrayType == null) {
      assert arrayAccess.array().getType().isNullType();
      return true;
    }
    assert arrayAccess.getMemberType() != MemberType.OBJECT
        || arrayType.getNesting() > 1
        || arrayType.getBaseType().isReferenceType();
    return true;
  }

  private DexType getEnumTypeOrNull(Value receiver, Map<Instruction, DexType> convertedEnums) {
    TypeElement type = receiver.getType();
    if (type.isInt()) {
      return convertedEnums.get(receiver.definition);
    }
    if (!type.isClassType()) {
      return null;
    }
    DexType enumType = type.asClassType().getClassType();
    return enumsToUnbox.containsEnum(enumType) ? enumType : null;
  }

  public String compatibleName(DexType type) {
    return type.toSourceString().replace('.', '$');
  }

  private DexField createValuesField(DexType type) {
    return factory.createField(
        factory.enumUnboxingUtilityType,
        factory.intArrayType,
        factory.enumValuesFieldName + "$field$" + compatibleName(type));
  }

  private DexEncodedField computeValuesEncodedField(DexField field) {
    return new DexEncodedField(
        field,
        FieldAccessFlags.fromSharedAccessFlags(
            Constants.ACC_SYNTHETIC | Constants.ACC_STATIC | Constants.ACC_PUBLIC),
        DexAnnotationSet.empty(),
        null);
  }

  private DexMethod createValuesMethod(DexType type) {
    return factory.createMethod(
        factory.enumUnboxingUtilityType,
        factory.createProto(factory.intArrayType),
        factory.enumValuesFieldName + "$method$" + compatibleName(type));
  }

  private DexEncodedMethod computeValuesEncodedMethod(
      DexMethod method, DexField fieldValues, int numEnumInstances) {
    CfCode cfCode =
        new EnumUnboxingCfCodeProvider.EnumUnboxingValuesCfCodeProvider(
                appView,
                factory.enumUnboxingUtilityType,
                fieldValues,
                numEnumInstances,
                valuesUtilityMethod)
            .generateCfCode();
    return synthesizeUtilityMethod(cfCode, method, true);
  }

  private DexMethod computeInstanceFieldUtilityMethod(DexType enumType, DexField field) {
    assert enumsToUnbox.containsEnum(enumType);
    assert field.holder == enumType || field.holder == factory.enumType;
    String methodName =
        "get"
            + (enumType == field.holder ? "" : "Enum$")
            + field.name
            + "$$"
            + compatibleName(enumType);
    DexMethod fieldMethod =
        factory.createMethod(
            factory.enumUnboxingUtilityType,
            factory.createProto(field.type, factory.intType),
            methodName);
    utilityMethods.computeIfAbsent(
        fieldMethod, m -> synthesizeInstanceFieldMethod(m, enumType, field, null));
    return fieldMethod;
  }

  private DexMethod computeStringValueOfUtilityMethod(DexType enumType) {
    // TODO(b/167994636): remove duplication between instance field name read and this method.
    assert enumsToUnbox.containsEnum(enumType);
    String methodName = "string$valueOf$" + compatibleName(enumType);
    DexMethod fieldMethod =
        factory.createMethod(
            factory.enumUnboxingUtilityType,
            factory.createProto(factory.stringType, factory.intType),
            methodName);
    AbstractValue nullString =
        appView.abstractValueFactory().createSingleStringValue(factory.createString("null"));
    utilityMethods.computeIfAbsent(
        fieldMethod,
        m -> synthesizeInstanceFieldMethod(m, enumType, factory.enumMembers.nameField, nullString));
    return fieldMethod;
  }

  private DexMethod computeValueOfUtilityMethod(DexType type) {
    assert enumsToUnbox.containsEnum(type);
    DexMethod valueOf =
        factory.createMethod(
            factory.enumUnboxingUtilityType,
            factory.createProto(factory.intType, factory.stringType),
            "valueOf" + compatibleName(type));
    utilityMethods.computeIfAbsent(valueOf, m -> synthesizeValueOfUtilityMethod(m, type));
    return valueOf;
  }

  private DexType getEnumTypeOrNull(ArrayAccess arrayAccess) {
    ArrayTypeElement arrayType = arrayAccess.array().getType().asArrayType();
    if (arrayType == null) {
      assert arrayAccess.array().getType().isNullType();
      return null;
    }
    if (arrayType.getNesting() != 1) {
      return null;
    }
    TypeElement baseType = arrayType.getBaseType();
    if (!baseType.isClassType()) {
      return null;
    }
    DexType classType = baseType.asClassType().getClassType();
    return enumsToUnbox.containsEnum(classType) ? classType : null;
  }

  void synthesizeEnumUnboxingUtilityMethods(
      Builder<?> builder, IRConverter converter, ExecutorService executorService)
      throws ExecutionException {
    // Synthesize a class which holds various utility methods that may be called from the IR
    // rewriting. If any of these methods are not used, they will be removed by the Enqueuer.
    List<DexEncodedMethod> requiredMethods = new ArrayList<>(utilityMethods.values());
    // Sort for deterministic order.
    requiredMethods.sort((m1, m2) -> m1.method.name.slowCompareTo(m2.method.name));
    if (requiredMethods.isEmpty()) {
      return;
    }
    List<DexEncodedField> fields = new ArrayList<>(extraUtilityFields.values());
    fields.sort((f1, f2) -> f1.field.name.slowCompareTo(f2.field.name));
    DexProgramClass utilityClass =
        appView.definitionForProgramType(factory.enumUnboxingUtilityType);
    assert utilityClass != null : "Should have been synthesized upfront.";
    utilityClass.appendStaticFields(fields);
    utilityClass.addDirectMethods(requiredMethods);
    assert requiredMethods.stream().allMatch(DexEncodedMethod::isPublic);
    // TODO(b/147860220): Use processMethodsConcurrently on requiredMethods instead.
    converter.optimizeSynthesizedClass(utilityClass, executorService);
  }

  public static DexProgramClass synthesizeEmptyEnumUnboxingUtilityClass(AppView<?> appView) {
    DexItemFactory factory = appView.dexItemFactory();
    return new DexProgramClass(
        factory.enumUnboxingUtilityType,
        null,
        new SynthesizedOrigin("EnumUnboxing ", EnumUnboxingRewriter.class),
        ClassAccessFlags.fromSharedAccessFlags(Constants.ACC_PUBLIC | Constants.ACC_SYNTHETIC),
        factory.objectType,
        DexTypeList.empty(),
        factory.createString("enumunboxing"),
        null,
        Collections.emptyList(),
        null,
        Collections.emptyList(),
        DexAnnotationSet.empty(),
        DexEncodedField.EMPTY_ARRAY,
        DexEncodedField.EMPTY_ARRAY,
        DexEncodedMethod.EMPTY_ARRAY,
        DexEncodedMethod.EMPTY_ARRAY,
        factory.getSkipNameValidationForTesting(),
        DexProgramClass::checksumFromType);
  }

  private DexEncodedMethod synthesizeInstanceFieldMethod(
      DexMethod method, DexType enumType, DexField field, AbstractValue nullValue) {
    assert method.proto.returnType == field.type;
    assert unboxedEnumsInstanceFieldData.getInstanceFieldData(enumType, field).isMapping();
    CfCode cfCode =
        new EnumUnboxingCfCodeProvider.EnumUnboxingInstanceFieldCfCodeProvider(
                appView,
                factory.enumUnboxingUtilityType,
                field.type,
                enumsToUnbox.getEnumValueInfoMap(enumType),
                unboxedEnumsInstanceFieldData
                    .getInstanceFieldData(enumType, field)
                    .asEnumFieldMappingData(),
                nullValue)
            .generateCfCode();
    return synthesizeUtilityMethod(cfCode, method, false);
  }

  private DexEncodedMethod synthesizeValueOfUtilityMethod(DexMethod method, DexType enumType) {
    assert method.proto.returnType == factory.intType;
    assert unboxedEnumsInstanceFieldData
        .getInstanceFieldData(enumType, factory.enumMembers.nameField)
        .isMapping();
    CfCode cfCode =
        new EnumUnboxingCfCodeProvider.EnumUnboxingValueOfCfCodeProvider(
                appView,
                factory.enumUnboxingUtilityType,
                enumType,
                enumsToUnbox.getEnumValueInfoMap(enumType),
                unboxedEnumsInstanceFieldData
                    .getInstanceFieldData(enumType, factory.enumMembers.nameField)
                    .asEnumFieldMappingData())
            .generateCfCode();
    return synthesizeUtilityMethod(cfCode, method, false);
  }

  private DexEncodedMethod synthesizeZeroCheckMethod() {
    CfCode cfCode =
        EnumUnboxingCfMethods.EnumUnboxingMethods_zeroCheck(appView.options(), zeroCheckMethod);
    return synthesizeUtilityMethod(cfCode, zeroCheckMethod, false);
  }

  private DexEncodedMethod synthesizeZeroCheckMessageMethod() {
    CfCode cfCode =
        EnumUnboxingCfMethods.EnumUnboxingMethods_zeroCheckMessage(
            appView.options(), zeroCheckMessageMethod);
    return synthesizeUtilityMethod(cfCode, zeroCheckMessageMethod, false);
  }

  private DexEncodedMethod synthesizeOrdinalMethod() {
    CfCode cfCode =
        EnumUnboxingCfMethods.EnumUnboxingMethods_ordinal(appView.options(), ordinalUtilityMethod);
    return synthesizeUtilityMethod(cfCode, ordinalUtilityMethod, false);
  }

  private DexEncodedMethod synthesizeEqualsMethod() {
    CfCode cfCode =
        EnumUnboxingCfMethods.EnumUnboxingMethods_equals(appView.options(), equalsUtilityMethod);
    return synthesizeUtilityMethod(cfCode, equalsUtilityMethod, false);
  }

  private DexEncodedMethod synthesizeCompareToMethod() {
    CfCode cfCode =
        EnumUnboxingCfMethods.EnumUnboxingMethods_compareTo(
            appView.options(), compareToUtilityMethod);
    return synthesizeUtilityMethod(cfCode, compareToUtilityMethod, false);
  }

  private DexEncodedMethod synthesizeValuesUtilityMethod() {
    CfCode cfCode =
        EnumUnboxingCfMethods.EnumUnboxingMethods_values(appView.options(), valuesUtilityMethod);
    return synthesizeUtilityMethod(cfCode, valuesUtilityMethod, false);
  }

  private DexEncodedMethod synthesizeUtilityMethod(CfCode cfCode, DexMethod method, boolean sync) {
    return new DexEncodedMethod(
        method,
        synthesizedMethodAccessFlags(sync),
        DexAnnotationSet.empty(),
        ParameterAnnotationsList.empty(),
        cfCode,
        REQUIRED_CLASS_FILE_VERSION,
        true);
  }

  private MethodAccessFlags synthesizedMethodAccessFlags(boolean sync) {
    int access = Constants.ACC_PUBLIC | Constants.ACC_SYNTHETIC | Constants.ACC_STATIC;
    if (sync) {
      access = access | Constants.ACC_SYNCHRONIZED;
    }
    return MethodAccessFlags.fromSharedAccessFlags(access, false);
  }
}
