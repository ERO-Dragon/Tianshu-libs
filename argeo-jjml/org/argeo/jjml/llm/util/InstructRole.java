// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm.util;

import java.util.function.Supplier;

public enum InstructRole implements Supplier<String> {
   SYSTEM("system"),
   USER("user"),
   ASSISTANT("assistant");

   private final String role;

   private InstructRole(String role) {
      this.role = role;
   }

   public String get() {
      return this.role;
   }

   public String toString() {
      return this.get();
   }
}
