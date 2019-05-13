package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.ClassAccessFlags;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.NestMemberClassAttribute;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.origin.SynthesizedOrigin;
import com.android.tools.r8.utils.BooleanUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

// NestBasedAccessDesugaring contains common code between the two subclasses
// which are specialized for d8 and r8
public abstract class NestBasedAccessDesugaring {

  // Short names to avoid creating long strings
  private static final String NEST_ACCESS_NAME_PREFIX = "-$$Nest$";
  private static final String NEST_ACCESS_METHOD_NAME_PREFIX = NEST_ACCESS_NAME_PREFIX + "m";
  private static final String NEST_ACCESS_STATIC_METHOD_NAME_PREFIX =
      NEST_ACCESS_NAME_PREFIX + "sm";
  private static final String NEST_ACCESS_FIELD_GET_NAME_PREFIX = NEST_ACCESS_NAME_PREFIX + "fget";
  private static final String NEST_ACCESS_STATIC_GET_FIELD_NAME_PREFIX =
      NEST_ACCESS_NAME_PREFIX + "sfget";
  private static final String NEST_ACCESS_FIELD_PUT_NAME_PREFIX = NEST_ACCESS_NAME_PREFIX + "fput";
  private static final String NEST_ACCESS_STATIC_PUT_FIELD_NAME_PREFIX =
      NEST_ACCESS_NAME_PREFIX + "sfput";
  public static final String NEST_CONSTRUCTOR_NAME = NEST_ACCESS_NAME_PREFIX + "Constructor";
  private static final String FULL_NEST_CONTRUCTOR_NAME = "L" + NEST_CONSTRUCTOR_NAME + ";";

  protected final AppView<?> appView;
  // Following maps are there to avoid creating the bridges multiple times.
  private final Map<DexEncodedMethod, DexMethod> bridges = new ConcurrentHashMap<>();
  private final Map<DexEncodedField, DexMethod> getFieldBridges = new ConcurrentHashMap<>();
  private final Map<DexEncodedField, DexMethod> putFieldBridges = new ConcurrentHashMap<>();
  // The following map records the bridges to add in the program.
  // It may differ from the values of the previous maps
  // if some classes are on the classpath and not the program path.
  final Map<DexEncodedMethod, DexProgramClass> deferredBridgesToAdd = new ConcurrentHashMap<>();
  // Common single empty class for nest based private constructors
  private final DexProgramClass nestConstructor;
  private boolean nestConstructorUsed = false;

  NestBasedAccessDesugaring(AppView<?> appView) {
    this.appView = appView;
    this.nestConstructor = createNestAccessConstructor();
  }

  DexType getNestConstructorType() {
    return nestConstructor.type;
  }

  // Extract the list of types in the programClass' nest, of host hostClass
  List<DexType> extractNest(DexClass hostClass, DexClass clazz) {
    assert clazz != null;
    if (hostClass == null) {
      throw abortCompilationDueToMissingNestHost(clazz);
    }
    List<DexType> classesInNest =
        new ArrayList<>(hostClass.getNestMembersClassAttributes().size() + 1);
    for (NestMemberClassAttribute nestmate : hostClass.getNestMembersClassAttributes()) {
      classesInNest.add(nestmate.getNestMember());
    }
    classesInNest.add(hostClass.type);
    return classesInNest;
  }

  Future<?> asyncProcessNest(List<DexType> nest, ExecutorService executorService) {
    return executorService.submit(
        () -> {
          processNest(nest);
          return null; // we want a Callable not a Runnable to be able to throw
        });
  }

  private void processNest(List<DexType> nest) {
    for (DexType type : nest) {
      DexClass clazz = appView.definitionFor(type);
      if (clazz == null) {
        // TODO(b/130529338) We could throw only a warning if a class is missing.
        throw abortCompilationDueToIncompleteNest(nest);
      }
      if (shouldProcessClassInNest(clazz, nest)) {
        NestBasedAccessDesugaringUseRegistry registry =
            new NestBasedAccessDesugaringUseRegistry(nest, clazz);
        for (DexEncodedMethod method : clazz.methods()) {
          method.registerCodeReferences(registry);
        }
      }
    }
  }

  protected abstract boolean shouldProcessClassInNest(DexClass clazz, List<DexType> nest);

  void addDeferredBridges() {
    for (Map.Entry<DexEncodedMethod, DexProgramClass> entry : deferredBridgesToAdd.entrySet()) {
      entry.getValue().addMethod(entry.getKey());
    }
  }

  private RuntimeException abortCompilationDueToIncompleteNest(List<DexType> nest) {
    List<String> programClassesFromNest = new ArrayList<>();
    List<String> unavailableClasses = new ArrayList<>();
    List<String> classPathClasses = new ArrayList<>();
    List<String> libraryClasses = new ArrayList<>();
    for (DexType type : nest) {
      DexClass clazz = appView.definitionFor(type);
      if (clazz == null) {
        unavailableClasses.add(type.getName());
      } else if (clazz.isLibraryClass()) {
        libraryClasses.add(type.getName());
      } else if (clazz.isProgramClass()) {
        programClassesFromNest.add(type.getName());
      } else {
        assert clazz.isClasspathClass();
        classPathClasses.add(type.getName());
      }
    }
    StringBuilder stringBuilder =
        new StringBuilder("Compilation of classes ")
            .append(String.join(", ", programClassesFromNest))
            .append(" requires its nest mates ");
    if (!unavailableClasses.isEmpty()) {
      stringBuilder.append(String.join(", ", unavailableClasses)).append(" (unavailable) ");
    }
    if (!libraryClasses.isEmpty()) {
      stringBuilder.append(String.join(", ", unavailableClasses)).append(" (on library path) ");
    }
    stringBuilder.append("to be on program or class path for compilation to succeed)");
    if (!classPathClasses.isEmpty()) {
      stringBuilder
          .append("(Classes ")
          .append(String.join(", ", classPathClasses))
          .append(" from the same nest are on class path).");
    }
    throw new CompilationError(stringBuilder.toString());
  }

  private RuntimeException abortCompilationDueToMissingNestHost(DexClass compiledClass) {
    String nestHostName = compiledClass.getNestHostClassAttribute().getNestHost().getName();
    throw new CompilationError(
        "Class "
            + compiledClass.type.getName()
            + " requires its nest host "
            + nestHostName
            + " to be on program or class path for compilation to succeed.");
  }

  private DexProgramClass createNestAccessConstructor() {
    return new DexProgramClass(
        appView.dexItemFactory().createType(FULL_NEST_CONTRUCTOR_NAME),
        null,
        new SynthesizedOrigin("Nest based access desugaring", getClass()),
        // Make the synthesized class public since shared in the whole program.
        ClassAccessFlags.fromDexAccessFlags(
            Constants.ACC_FINAL | Constants.ACC_SYNTHETIC | Constants.ACC_PUBLIC),
        appView.dexItemFactory().objectType,
        DexTypeList.empty(),
        appView.dexItemFactory().createString("nest"),
        null,
        Collections.emptyList(),
        null,
        Collections.emptyList(),
        DexAnnotationSet.empty(),
        DexEncodedField.EMPTY_ARRAY,
        DexEncodedField.EMPTY_ARRAY,
        DexEncodedMethod.EMPTY_ARRAY,
        DexEncodedMethod.EMPTY_ARRAY,
        appView.dexItemFactory().getSkipNameValidationForTesting());
  }

  void synthetizeNestConstructor(DexApplication.Builder<?> builder) {
    if (nestConstructorUsed) {
      appView.appInfo().addSynthesizedClass(nestConstructor);
      builder.addSynthesizedClass(nestConstructor, true);
    }
  }

  public static boolean isNestConstructor(DexType type) {
    return type.getName().equals(NEST_CONSTRUCTOR_NAME);
  }

  private static DexString computeMethodBridgeName(DexEncodedMethod method, AppView<?> appView) {
    String methodName = method.method.name.toString();
    String fullName;
    if (method.isStatic()) {
      fullName = NEST_ACCESS_STATIC_METHOD_NAME_PREFIX + methodName;
    } else {
      fullName = NEST_ACCESS_METHOD_NAME_PREFIX + methodName;
    }
    return appView.dexItemFactory().createString(fullName);
  }

  private static DexString computeFieldBridgeName(
      DexEncodedField field, boolean isGet, AppView<?> appView) {
    String fieldName = field.field.name.toString();
    String fullName;
    if (isGet && !field.isStatic()) {
      fullName = NEST_ACCESS_FIELD_GET_NAME_PREFIX + fieldName;
    } else if (isGet) {
      fullName = NEST_ACCESS_STATIC_GET_FIELD_NAME_PREFIX + fieldName;
    } else if (!field.isStatic()) {
      fullName = NEST_ACCESS_FIELD_PUT_NAME_PREFIX + fieldName;
    } else {
      fullName = NEST_ACCESS_STATIC_PUT_FIELD_NAME_PREFIX + fieldName;
    }
    return appView.dexItemFactory().createString(fullName);
  }

  static DexMethod computeMethodBridge(DexEncodedMethod encodedMethod, AppView<?> appView) {
    DexMethod method = encodedMethod.method;
    DexProto proto =
        encodedMethod.accessFlags.isStatic()
            ? method.proto
            : appView.dexItemFactory().prependTypeToProto(method.holder, method.proto);
    return appView
        .dexItemFactory()
        .createMethod(method.holder, proto, computeMethodBridgeName(encodedMethod, appView));
  }

  static DexMethod computeInitializerBridge(
      DexMethod method, AppView<?> appView, DexType nestConstructorType) {
    DexProto newProto =
        appView.dexItemFactory().appendTypeToProto(method.proto, nestConstructorType);
    return appView.dexItemFactory().createMethod(method.holder, newProto, method.name);
  }

  static DexMethod computeFieldBridge(DexEncodedField field, boolean isGet, AppView<?> appView) {
    DexType holderType = field.field.holder;
    DexType fieldType = field.field.type;
    int bridgeParameterCount =
        BooleanUtils.intValue(!field.isStatic()) + BooleanUtils.intValue(!isGet);
    DexType[] parameters = new DexType[bridgeParameterCount];
    if (!isGet) {
      parameters[parameters.length - 1] = fieldType;
    }
    if (!field.isStatic()) {
      parameters[0] = holderType;
    }
    DexType returnType = isGet ? fieldType : appView.dexItemFactory().voidType;
    DexProto proto = appView.dexItemFactory().createProto(returnType, parameters);
    return appView
        .dexItemFactory()
        .createMethod(holderType, proto, computeFieldBridgeName(field, isGet, appView));
  }

  static boolean invokeRequiresRewriting(
      DexEncodedMethod method, List<DexType> contextNest, DexType contextType) {
    // Rewrite only when targeting other nest members private fields.
    return method.accessFlags.isPrivate()
        && method.method.holder != contextType
        && contextNest.contains(method.method.holder);
  }

  static boolean fieldAccessRequiresRewriting(
      DexEncodedField field, List<DexType> contextNest, DexType contextType) {
    // Rewrite only when targeting other nest members private fields.
    return field.accessFlags.isPrivate()
        && field.field.holder != contextType
        && contextNest.contains(field.field.holder);
  }

  private boolean holderRequiresBridge(DexClass holder) {
    // Bridges are added on program classes only.
    // Bridges on class paths are added in different compilation units.
    if (holder.isProgramClass()) {
      return false;
    } else if (holder.isClasspathClass()) {
      return true;
    }
    assert holder.isLibraryClass();
    DexClass host = appView.definitionFor(holder.getNestHost());
    throw abortCompilationDueToIncompleteNest(extractNest(host, holder));
  }

  DexMethod ensureFieldAccessBridge(DexEncodedField field, boolean isGet) {
    DexClass holder = appView.definitionFor(field.field.holder);
    assert holder != null;
    DexMethod bridgeMethod = computeFieldBridge(field, isGet, appView);
    if (holderRequiresBridge(holder)) {
      return bridgeMethod;
    }
    // The map is used to avoid creating multiple times the bridge.
    Map<DexEncodedField, DexMethod> fieldMap = isGet ? getFieldBridges : putFieldBridges;
    return fieldMap.computeIfAbsent(
        field,
        k -> {
          DexEncodedMethod localBridge =
              DexEncodedMethod.createFieldAccessorBridge(
                  new DexFieldWithAccess(field, isGet), holder, bridgeMethod);
          deferredBridgesToAdd.put(localBridge, holder.asProgramClass());
          return bridgeMethod;
        });
  }

  DexMethod ensureInvokeBridge(DexEncodedMethod method) {
    // We add bridges only when targeting other nest members.
    DexClass holder = appView.definitionFor(method.method.holder);
    assert holder != null;
    DexMethod bridgeMethod;
    if (method.isInstanceInitializer()) {
      nestConstructorUsed = true;
      bridgeMethod = computeInitializerBridge(method.method, appView, nestConstructor.type);
    } else {
      bridgeMethod = computeMethodBridge(method, appView);
    }
    if (holderRequiresBridge(holder)) {
      return bridgeMethod;
    }
    // The map is used to avoid creating multiple times the bridge.
    return bridges.computeIfAbsent(
        method,
        k -> {
          DexEncodedMethod localBridge =
              method.isInstanceInitializer()
                  ? method.toInitializerForwardingBridge(holder, bridgeMethod)
                  : method.toStaticForwardingBridge(holder, computeMethodBridge(method, appView));
          deferredBridgesToAdd.put(localBridge, holder.asProgramClass());
          return bridgeMethod;
        });
  }

  protected class NestBasedAccessDesugaringUseRegistry extends UseRegistry {

    private final List<DexType> nest;
    private final DexClass currentClass;

    NestBasedAccessDesugaringUseRegistry(List<DexType> nest, DexClass currentClass) {
      super(appView.options().itemFactory);
      this.nest = nest;
      this.currentClass = currentClass;
    }

    private boolean registerInvoke(DexMethod method) {
      DexEncodedMethod encodedMethod = appView.definitionFor(method);
      if (encodedMethod != null
          && invokeRequiresRewriting(encodedMethod, nest, currentClass.type)) {
        ensureInvokeBridge(encodedMethod);
        return true;
      }
      return false;
    }

    private boolean registerFieldAccess(DexField field, boolean isGet) {
      DexEncodedField encodedField = appView.definitionFor(field);
      if (encodedField != null
          && fieldAccessRequiresRewriting(encodedField, nest, currentClass.type)) {
        ensureFieldAccessBridge(encodedField, isGet);
        return true;
      }
      return false;
    }

    @Override
    public boolean registerInvokeVirtual(DexMethod method) {
      // Calls to class nest mate private methods are targeted by invokeVirtual in jdk11.
      // The spec recommends to do so, but do not enforce it, hence invokeDirect is also registered.
      return registerInvoke(method);
    }

    @Override
    public boolean registerInvokeDirect(DexMethod method) {
      return registerInvoke(method);
    }

    @Override
    public boolean registerInvokeStatic(DexMethod method) {
      return registerInvoke(method);
    }

    @Override
    public boolean registerInvokeInterface(DexMethod method) {
      // Calls to interface nest mate private methods are targeted by invokeInterface in jdk11.
      // The spec recommends to do so, but do not enforce it, hence invokeDirect is also registered.
      return registerInvoke(method);
    }

    @Override
    public boolean registerInvokeSuper(DexMethod method) {
      // Cannot target private method.
      return false;
    }

    @Override
    public boolean registerInstanceFieldWrite(DexField field) {
      return registerFieldAccess(field, false);
    }

    @Override
    public boolean registerInstanceFieldRead(DexField field) {
      return registerFieldAccess(field, true);
    }

    @Override
    public boolean registerNewInstance(DexType type) {
      // Unrelated to access based control.
      // The <init> method has to be rewritten instead
      // and <init> is called through registerInvoke.
      return false;
    }

    @Override
    public boolean registerStaticFieldRead(DexField field) {
      return registerFieldAccess(field, true);
    }

    @Override
    public boolean registerStaticFieldWrite(DexField field) {
      return registerFieldAccess(field, false);
    }

    @Override
    public boolean registerTypeReference(DexType type) {
      // Unrelated to access based control.
      return false;
    }
  }

  public static final class DexFieldWithAccess {

    private final DexEncodedField field;
    private final boolean isGet;

    DexFieldWithAccess(DexEncodedField field, boolean isGet) {
      this.field = field;
      this.isGet = isGet;
    }

    @Override
    public int hashCode() {
      return Objects.hash(field, isGet);
    }

    @Override
    public boolean equals(Object o) {
      if (o == null) {
        return false;
      }
      if (getClass() != o.getClass()) {
        return false;
      }
      DexFieldWithAccess other = (DexFieldWithAccess) o;
      return isGet == other.isGet && field == other.field;
    }

    public boolean isGet() {
      return isGet;
    }

    public boolean isStatic() {
      return field.accessFlags.isStatic();
    }

    public boolean isPut() {
      return !isGet();
    }

    public boolean isInstance() {
      return !isStatic();
    }

    public boolean isStaticGet() {
      return isStatic() && isGet();
    }

    public boolean isStaticPut() {
      return isStatic() && isPut();
    }

    public boolean isInstanceGet() {
      return isInstance() && isGet();
    }

    public boolean isInstancePut() {
      return isInstance() && isPut();
    }

    public DexType getType() {
      return field.field.type;
    }

    public DexType getHolder() {
      return field.field.holder;
    }

    public DexField getField() {
      return field.field;
    }
  }
}
