// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.nio.ByteBuffer;

public interface LlamaCppContextState {
   void save(LlamaCppContext var1, int var2);

   int load(LlamaCppContext var1);

   public static class ByteBufferSavedState implements LlamaCppContextState {
      private ByteBuffer savedState;
      private int savedContextPosition;

      public ByteBufferSavedState() {
      }

      public void save(LlamaCppContext context, int contextPosition) {
         int stateSize = (int)context.getStateSize();
         this.savedState = ByteBuffer.allocateDirect(stateSize);
         context.readState(this.savedState);
         this.savedContextPosition = contextPosition;
      }

      public int load(LlamaCppContext context) {
         this.savedState.flip();
         context.writeState(this.savedState);
         return this.savedContextPosition;
      }
   }
}
