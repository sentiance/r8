// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.position.Position;
import java.util.List;

public class ReprocessClassInitializerRule extends ProguardConfigurationRule {

  public enum Type {
    ALWAYS,
    NEVER
  }

  public static class Builder
      extends ProguardConfigurationRule.Builder<ReprocessClassInitializerRule, Builder> {

    private Type type;

    private Builder() {
      super();
    }

    public Builder setType(Type type) {
      this.type = type;
      return this;
    }

    @Override
    public Builder self() {
      return this;
    }

    @Override
    public ReprocessClassInitializerRule build() {
      return new ReprocessClassInitializerRule(
          origin,
          getPosition(),
          source,
          classAnnotation,
          classAccessFlags,
          negatedClassAccessFlags,
          classTypeNegated,
          classType,
          classNames,
          inheritanceAnnotation,
          inheritanceClassName,
          inheritanceIsExtends,
          memberRules,
          type);
    }
  }

  private final Type type;

  private ReprocessClassInitializerRule(
      Origin origin,
      Position position,
      String source,
      ProguardTypeMatcher classAnnotation,
      ProguardAccessFlags classAccessFlags,
      ProguardAccessFlags negatedClassAccessFlags,
      boolean classTypeNegated,
      ProguardClassType classType,
      ProguardClassNameList classNames,
      ProguardTypeMatcher inheritanceAnnotation,
      ProguardTypeMatcher inheritanceClassName,
      boolean inheritanceIsExtends,
      List<ProguardMemberRule> memberRules,
      Type type) {
    super(
        origin,
        position,
        source,
        classAnnotation,
        classAccessFlags,
        negatedClassAccessFlags,
        classTypeNegated,
        classType,
        classNames,
        inheritanceAnnotation,
        inheritanceClassName,
        inheritanceIsExtends,
        memberRules);
    this.type = type;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Type getType() {
    return type;
  }

  @Override
  public boolean isReprocessClassInitializerRule() {
    return true;
  }

  @Override
  public ReprocessClassInitializerRule asReprocessClassInitializerRule() {
    return this;
  }

  @Override
  String typeString() {
    switch (type) {
      case ALWAYS:
        return "reprocessclassinitializer";
      case NEVER:
        return "neverreprocessclassinitializer";
      default:
        throw new Unreachable();
    }
  }
}
