// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.util.function.Supplier;

public class LlamaCppChatMessage {
   private final String role;
   private final String content;

   public LlamaCppChatMessage(String role, String content) {
      this.role = role;
      this.content = content;
   }

   public LlamaCppChatMessage(Supplier<String> role, String content) {
      this((String)role.get(), content);
   }

   public String getRole() {
      return this.role;
   }

   public String getContent() {
      return this.content;
   }
}
