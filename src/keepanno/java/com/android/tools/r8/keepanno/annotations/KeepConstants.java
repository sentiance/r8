// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.keepanno.annotations;

/**
 * Utility class for referencing the various keep annotations and their structure.
 *
 * <p>Use of these references avoids poluting the Java namespace with imports of the java
 * annotations which overlap in name with the actual semantic AST types.
 */
public final class KeepConstants {

  public static String getDescriptor(Class<?> clazz) {
    return "L" + clazz.getTypeName().replace('.', '/') + ";";
  }

  public static String getBinaryNameFromClassTypeName(String classTypeName) {
    return classTypeName.replace('.', '/');
  }

  public static final class Edge {
    public static final Class<KeepEdge> CLASS = KeepEdge.class;
    public static final String DESCRIPTOR = getDescriptor(CLASS);
    public static final String preconditions = "preconditions";
    public static final String consequences = "consequences";
  }

  public static final class UsesReflection {
    public static final Class<com.android.tools.r8.keepanno.annotations.UsesReflection> CLASS =
        com.android.tools.r8.keepanno.annotations.UsesReflection.class;
    public static final String DESCRIPTOR = getDescriptor(CLASS);
    public static final String value = "value";
    public static final String additionalPreconditions = "additionalPreconditions";
  }

  // Implicit hidden item which is "super type" of Condition and Target.
  public static final class Item {
    public static final String classConstant = "classConstant";
    public static final String methodName = "methodName";
    public static final String fieldName = "fieldName";
  }

  public static final class Condition {
    public static final Class<KeepCondition> CLASS = KeepCondition.class;
    public static final String DESCRIPTOR = getDescriptor(CLASS);
  }

  public static final class Target {
    public static final Class<KeepTarget> CLASS = KeepTarget.class;
    public static final String DESCRIPTOR = getDescriptor(CLASS);
  }
}
