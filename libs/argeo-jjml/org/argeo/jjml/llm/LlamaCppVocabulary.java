// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public class LlamaCppVocabulary {
   private final LlamaCppModel model;

   public LlamaCppVocabulary(LlamaCppModel model) {
      this.model = model;
   }

   private static native int[] doTokenizeUtf8BytesAsArray(long var0, byte[] var2, int var3, int var4, boolean var5, boolean var6);

   private static native int[] doTokenizeUtf8AsArray(long var0, ByteBuffer var2, int var3, int var4, boolean var5, boolean var6);

   private static native int doTokenizeUtf8(long var0, ByteBuffer var2, int var3, int var4, IntBuffer var5, int var6, int var7, boolean var8, boolean var9);

   private static native byte[] doDeTokenizeArrayAsUtf8Bytes(long var0, int[] var2, int var3, int var4, boolean var5, boolean var6);

   private static native byte[] doDeTokenizeAsUtf8Bytes(long var0, IntBuffer var2, int var3, int var4, boolean var5, boolean var6);

   private static native int doDeTokenizeAsUtf8(long var0, IntBuffer var2, int var3, int var4, ByteBuffer var5, int var6, int var7, boolean var8, boolean var9);

   public void tokenize(CharSequence str, IntBuffer tokens, boolean addSpecial, boolean parseSpecial) {
      CharBuffer chars = CharBuffer.wrap(str);
      ByteBuffer utf8 = StandardCharsets.UTF_8.encode(chars);
      this.tokenizeUtf8(utf8, tokens, addSpecial, parseSpecial);
   }

   public IntBuffer tokenize(CharSequence str, boolean addSpecial, boolean parseSpecial) {
      CharBuffer chars = CharBuffer.wrap(str);
      ByteBuffer utf8 = StandardCharsets.UTF_8.encode(chars);
      int[] arr = this.tokenizeUtf8(utf8, addSpecial, parseSpecial);
      return IntBuffer.wrap(arr);
   }

   public void tokenize(ByteBuffer utf8, IntBuffer tokens, boolean addSpecial, boolean parseSpecial) throws IndexOutOfBoundsException {
      this.tokenizeUtf8(utf8, tokens, addSpecial, parseSpecial);
   }

   public IntBuffer tokenize(ByteBuffer utf8, boolean addSpecial, boolean parseSpecial) {
      int[] arr = this.tokenizeUtf8(utf8, addSpecial, parseSpecial);
      return IntBuffer.wrap(arr);
   }

   public void deTokenize(IntBuffer in, ByteBuffer utf8, boolean removeSpecial, boolean unparseSpecial) throws IndexOutOfBoundsException {
      this.deTokenizeUtf8(in, utf8, removeSpecial, unparseSpecial);
   }

   public String deTokenize(IntBuffer in, boolean removeSpecial, boolean unparseSpecial) {
      byte[] bytes = this.deTokenizeUtf8(in, removeSpecial, unparseSpecial);
      return new String(bytes, StandardCharsets.UTF_8);
   }

   public final IntBuffer[] tokenizeMultiple(List<? extends CharSequence> prompts) {
      IntBuffer[] tokenLists = new IntBuffer[prompts.size()];

      for(int i = 0; i < prompts.size(); ++i) {
         CharSequence prompt = (CharSequence)prompts.get(i);
         IntBuffer tokenList = this.tokenize(prompt);
         tokenLists[i] = tokenList;
      }

      return tokenLists;
   }

   public final IntBuffer tokenize(CharSequence str) {
      return this.tokenize(str, false, true);
   }

   public final void tokenize(CharSequence str, IntBuffer tokens) throws IndexOutOfBoundsException {
      this.tokenize(str, tokens, false, true);
   }

   public final String deTokenize(IntBuffer in) {
      return this.deTokenize(in, true, false);
   }

   public final void deTokenize(IntBuffer in, ByteBuffer out) throws IndexOutOfBoundsException {
      this.deTokenize(in, out, true, false);
   }

   int[] tokenizeUtf8(ByteBuffer in, boolean addSpecial, boolean parseSpecial) {
      this.checkInput(in);
      synchronized(in) {
         int[] tokenArr;
         if (in.isDirect()) {
            tokenArr = doTokenizeUtf8AsArray(this.model.getAsLong(), in, in.position(), in.remaining(), addSpecial, parseSpecial);
            in.position(in.limit());
         } else if (in.hasArray() && !in.isReadOnly()) {
            byte[] arr = in.array();
            tokenArr = doTokenizeUtf8BytesAsArray(this.model.getAsLong(), arr, in.arrayOffset(), in.remaining(), addSpecial, parseSpecial);
            in.position(in.limit());
         } else {
            byte[] copy = new byte[in.remaining()];
            in.get(copy, in.position(), copy.length);
            tokenArr = doTokenizeUtf8BytesAsArray(this.model.getAsLong(), copy, 0, copy.length, addSpecial, parseSpecial);
         }

         return tokenArr;
      }
   }

   void tokenizeUtf8(ByteBuffer str, IntBuffer tokens, boolean addSpecial, boolean parseSpecial) throws IndexOutOfBoundsException {
      this.checkInput(str);
      this.checkOutput(tokens);
      synchronized(tokens) {
         if (str.isDirect() && tokens.isDirect()) {
            int count = doTokenizeUtf8(this.model.getAsLong(), str, str.position(), str.remaining(), tokens, tokens.position(), tokens.remaining(), addSpecial, parseSpecial);
            if (count < 0) {
               throw new IndexOutOfBoundsException(-count);
            }

            str.position(str.limit());
            tokens.position(tokens.position() + count);
         } else {
            int[] tokenArr = this.tokenizeUtf8(str, addSpecial, parseSpecial);
            if (tokenArr.length > tokens.remaining()) {
               throw new IndexOutOfBoundsException(tokenArr.length);
            }

            tokens.put(tokenArr);
         }

      }
   }

   byte[] deTokenizeUtf8(IntBuffer in, boolean removeSpecial, boolean unparseSpecial) {
      byte[] outArr;
      if (in.isDirect()) {
         outArr = doDeTokenizeAsUtf8Bytes(this.model.getAsLong(), in, in.position(), in.remaining(), removeSpecial, unparseSpecial);
         in.position(in.limit());
      } else if (in.hasArray() && !in.isReadOnly()) {
         outArr = doDeTokenizeArrayAsUtf8Bytes(this.model.getAsLong(), in.array(), in.arrayOffset(), in.remaining(), removeSpecial, unparseSpecial);
         in.position(in.limit());
      } else {
         int[] copy = new int[in.remaining()];
         in.get(copy, in.position(), copy.length);
         outArr = doDeTokenizeArrayAsUtf8Bytes(this.model.getAsLong(), copy, 0, copy.length, removeSpecial, unparseSpecial);
      }

      return outArr;
   }

   void deTokenizeUtf8(IntBuffer in, ByteBuffer str, boolean removeSpecial, boolean unparseSpecial) throws IndexOutOfBoundsException {
      if (in.isDirect() && str.isDirect()) {
         int count = doDeTokenizeAsUtf8(this.model.getAsLong(), in, in.position(), in.remaining(), str, str.position(), str.remaining(), removeSpecial, unparseSpecial);
         if (count < 0) {
            throw new IndexOutOfBoundsException(-count);
         }

         str.position(str.position() + count);
         in.position(in.limit());
      } else {
         byte[] bytes = this.deTokenizeUtf8(in, removeSpecial, unparseSpecial);
         if (bytes.length > str.limit() - str.position()) {
            throw new IndexOutOfBoundsException(bytes.length);
         }

         str.put(bytes);
      }

   }

   private void checkInput(Buffer in) {
      if (in instanceof IntBuffer && !ByteOrder.nativeOrder().equals(((IntBuffer)in).order())) {
         throw new IllegalArgumentException("Int buffer does not use native byte order");
      } else {
         Objects.requireNonNull(in, "Input buffer cannot be null");
      }
   }

   private void checkOutput(Buffer out) {
      Objects.requireNonNull(out, "Output buffer cannot be null");
      if (out.isReadOnly()) {
         throw new IllegalArgumentException("Output buffer is read-only");
      } else if (out instanceof IntBuffer && !ByteOrder.nativeOrder().equals(((IntBuffer)out).order())) {
         throw new IllegalArgumentException("Int buffer does not use native byte order");
      }
   }
}
