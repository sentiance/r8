// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.experimental.startup.profile;

import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.startup.StartupClassBuilder;
import com.android.tools.r8.utils.ClassReferenceUtils;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;

public class StartupClass extends StartupItem {

  private final DexType type;

  StartupClass(DexType type) {
    this.type = type;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Builder builder(DexItemFactory dexItemFactory) {
    return new Builder(dexItemFactory);
  }

  @Override
  public void accept(Consumer<StartupClass> classConsumer, Consumer<StartupMethod> methodConsumer) {
    classConsumer.accept(this);
  }

  @Override
  public <T> T apply(
      Function<StartupClass, T> classFunction, Function<StartupMethod, T> methodFunction) {
    return classFunction.apply(this);
  }

  @Override
  public DexType getReference() {
    return type;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    StartupClass that = (StartupClass) o;
    return type == that.type;
  }

  @Override
  public int hashCode() {
    return type.hashCode();
  }

  @Override
  public void write(Appendable appendable) throws IOException {
    appendable.append(getReference().toDescriptorString());
  }

  public static class Builder implements StartupClassBuilder {

    private final DexItemFactory dexItemFactory;

    private DexType type;

    Builder() {
      this(null);
    }

    Builder(DexItemFactory dexItemFactory) {
      this.dexItemFactory = dexItemFactory;
    }

    @Override
    public Builder setClassReference(ClassReference classReference) {
      assert dexItemFactory != null;
      return setClassReference(ClassReferenceUtils.toDexType(classReference, dexItemFactory));
    }

    public Builder setClassReference(DexType type) {
      this.type = type;
      return this;
    }

    public StartupClass build() {
      return new StartupClass(type);
    }
  }
}
