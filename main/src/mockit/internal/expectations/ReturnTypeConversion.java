/*
 * Copyright (c) 2006 JMockit developers
 * This file is subject to the terms of the MIT license (see LICENSE.txt).
 */
package mockit.internal.expectations;

import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.regex.*;
import javax.annotation.*;

import mockit.internal.expectations.invocation.*;
import mockit.internal.util.*;
import static mockit.internal.reflection.ConstructorReflection.*;
import static mockit.internal.reflection.MethodReflection.*;

@SuppressWarnings("OverlyComplexClass")
public final class ReturnTypeConversion
{
   private static final Class<?>[] STRING = {String.class};
   private static final Pattern JAVA_LANG = Pattern.compile("java.lang.", Pattern.LITERAL);

   @Nullable private final Expectation expectation;
   @Nonnull ExpectedInvocation invocation;
   @Nonnull private final Class<?> returnType;
   @Nonnull private final Object valueToReturn;

   ReturnTypeConversion(@Nonnull Expectation expectation, @Nonnull Class<?> returnType, @Nonnull Object value) {
      this.expectation = expectation;
      invocation = expectation.invocation;
      this.returnType = returnType;
      valueToReturn = value;
   }

   public ReturnTypeConversion(@Nonnull ExpectedInvocation invocation, @Nonnull Class<?> returnType, @Nonnull Object value) {
      expectation = null;
      this.invocation = invocation;
      this.returnType = returnType;
      valueToReturn = value;
   }

   @Nonnull
   public Object getConvertedValue() {
      Class<?> wrapperType = getWrapperType();
      Class<?> valueType = valueToReturn.getClass();

      if (valueType == wrapperType) {
         return valueToReturn;
      }

      if (wrapperType != null && AutoBoxing.isWrapperOfPrimitiveType(valueType)) {
         return getPrimitiveValueConvertingAsNeeded(wrapperType);
      }

      throw newIncompatibleTypesException();
   }

   @Nullable
   private Class<?> getWrapperType() {
      return AutoBoxing.isWrapperOfPrimitiveType(returnType) ? returnType : AutoBoxing.getWrapperType(returnType);
   }

   void addConvertedValue() {
      Class<?> wrapperType = getWrapperType();
      Class<?> valueType = valueToReturn.getClass();

      if (valueType == wrapperType) {
         addReturnValue(valueToReturn);
      }
      else if (wrapperType != null && AutoBoxing.isWrapperOfPrimitiveType(valueType)) {
         addPrimitiveValueConvertingAsNeeded(wrapperType);
      }
      else {
         boolean valueIsArray = valueType.isArray();

         if (valueIsArray || valueToReturn instanceof Iterable<?> || valueToReturn instanceof Iterator<?>) {
            addMultiValuedResultBasedOnTheReturnType(valueIsArray);
         }
         else if (wrapperType != null) {
            throw newIncompatibleTypesException();
         }
         else {
            addResultFromSingleValue();
         }
      }
   }

   private void addReturnValue(@Nonnull Object returnValue) {
      getInvocationResults().addReturnValueResult(returnValue);
   }

   @Nonnull
   private InvocationResults getInvocationResults() {
      assert expectation != null;
      return expectation.getResults();
   }

   private void addMultiValuedResultBasedOnTheReturnType(boolean valueIsArray) {
      if (returnType == void.class) {
         addMultiValuedResult(valueIsArray);
      }
      else if (returnType == Object.class) {
         addReturnValue(valueToReturn);
      }
      else if (valueIsArray && addCollectionOrMapWithElementsFromArray()) {
         return;
      }
      else if (hasReturnOfDifferentType()) {
         addMultiValuedResult(valueIsArray);
      }
      else {
         addReturnValue(valueToReturn);
      }
   }

   private boolean hasReturnOfDifferentType() {
      return
         !returnType.isArray() &&
         !Iterable.class.isAssignableFrom(returnType) && !Iterator.class.isAssignableFrom(returnType) &&
         !returnType.isAssignableFrom(valueToReturn.getClass());
   }

   private void addMultiValuedResult(boolean valueIsArray) {
      InvocationResults results = getInvocationResults();

      if (valueIsArray) {
         results.addResults(valueToReturn);
      }
      else if (valueToReturn instanceof Iterable<?>) {
         results.addResults((Iterable<?>) valueToReturn);
      }
      else {
         results.addDeferredResults((Iterator<?>) valueToReturn);
      }
   }

   private boolean addCollectionOrMapWithElementsFromArray() {
      int n = Array.getLength(valueToReturn);
      Object values = null;

      if (returnType.isAssignableFrom(ListIterator.class)) {
         List<Object> list = new ArrayList<>(n);
         addArrayElements(list, n);
         values = list.listIterator();
      }
      else if (returnType.isAssignableFrom(List.class)) {
         values = addArrayElements(new ArrayList<>(n), n);
      }
      else if (returnType.isAssignableFrom(Set.class)) {
         values = addArrayElements(new LinkedHashSet<>(n), n);
      }
      else if (returnType.isAssignableFrom(SortedSet.class)) {
         values = addArrayElements(new TreeSet<>(), n);
      }
      else if (returnType.isAssignableFrom(Map.class)) {
         values = addArrayElements(new LinkedHashMap<>(n), n);
      }
      else if (returnType.isAssignableFrom(SortedMap.class)) {
         values = addArrayElements(new TreeMap<>(), n);
      }

      if (values != null) {
         getInvocationResults().addReturnValue(values);
         return true;
      }

      return false;
   }

   @Nonnull
   private Object addArrayElements(@Nonnull Collection<Object> values, int elementCount) {
      for (int i = 0; i < elementCount; i++) {
         Object element = Array.get(valueToReturn, i);
         values.add(element);
      }

      return values;
   }

   @Nullable
   private Object addArrayElements(@Nonnull Map<Object, Object> values, int elementPairCount) {
      for (int i = 0; i < elementPairCount; i++) {
         Object keyAndValue = Array.get(valueToReturn, i);

         if (keyAndValue == null || !keyAndValue.getClass().isArray()) {
            return null;
         }

         Object key = Array.get(keyAndValue, 0);
         Object element = Array.getLength(keyAndValue) > 1 ? Array.get(keyAndValue, 1) : null;
         values.put(key, element);
      }

      return values;
   }

   @Nonnull
   private Object getResultFromSingleValue() {
      if (returnType == Object.class) {
         return valueToReturn;
      }
      else if (returnType == void.class) {
         throw newIncompatibleTypesException();
      }
      else if (valueToReturn instanceof CharSequence) {
         return getCharSequence((CharSequence) valueToReturn);
      }
      else {
         return getPrimitiveValue();
      }
   }

   @SuppressWarnings("OverlyComplexMethod")
   private void addResultFromSingleValue() {
      if (returnType == Object.class) {
         addReturnValue(valueToReturn);
      }
      else if (returnType == void.class) {
         throw newIncompatibleTypesException();
      }
      else if (returnType == byte[].class && valueToReturn instanceof CharSequence) {
         addReturnValue(valueToReturn.toString().getBytes());
      }
      else if (returnType.isArray()) {
         addArray();
      }
      else if (returnType.isAssignableFrom(ArrayList.class)) {
         addCollectionWithSingleElement(new ArrayList<>(1));
      }
      else if (returnType.isAssignableFrom(LinkedList.class)) {
         addCollectionWithSingleElement(new LinkedList<>());
      }
      else if (returnType.isAssignableFrom(HashSet.class)) {
         addCollectionWithSingleElement(new HashSet<>(1));
      }
      else if (returnType.isAssignableFrom(TreeSet.class)) {
         addCollectionWithSingleElement(new TreeSet<>());
      }
      else if (returnType.isAssignableFrom(ListIterator.class)) {
         addListIterator();
      }
      else if (valueToReturn instanceof CharSequence) {
         addCharSequence((CharSequence) valueToReturn);
      }
      else {
         addPrimitiveValue();
      }
   }

   @Nonnull
   private IllegalArgumentException newIncompatibleTypesException() {
      String valueTypeName = JAVA_LANG.matcher(valueToReturn.getClass().getName()).replaceAll("");
      String returnTypeName = JAVA_LANG.matcher(returnType.getName()).replaceAll("");

      MethodFormatter methodDesc = new MethodFormatter(invocation.getClassDesc(), invocation.getMethodNameAndDescription());
      String msg = "Value of type " + valueTypeName + " incompatible with return type " + returnTypeName + " of " + methodDesc;

      return new IllegalArgumentException(msg);
   }

   private void addArray() {
      Object array = Array.newInstance(returnType.getComponentType(), 1);
      Array.set(array, 0, valueToReturn);
      addReturnValue(array);
   }

   private void addCollectionWithSingleElement(@Nonnull Collection<Object> container) {
      container.add(valueToReturn);
      addReturnValue(container);
   }

   private void addListIterator() {
      List<Object> l = new ArrayList<>(1);
      l.add(valueToReturn);
      ListIterator<Object> iterator = l.listIterator();
      addReturnValue(iterator);
   }

   @Nonnull
   private Object getCharSequence(@Nonnull CharSequence textualValue) {
      @Nonnull Object convertedValue = textualValue;

      if (returnType.isAssignableFrom(ByteArrayInputStream.class)) {
         //noinspection resource
         convertedValue = new ByteArrayInputStream(textualValue.toString().getBytes());
      }
      else if (returnType.isAssignableFrom(StringReader.class)) {
         //noinspection resource
         convertedValue = new StringReader(textualValue.toString());
      }
      else if (!(textualValue instanceof StringBuilder) && returnType.isAssignableFrom(StringBuilder.class)) {
         convertedValue = new StringBuilder(textualValue);
      }
      else if (!(textualValue instanceof CharBuffer) && returnType.isAssignableFrom(CharBuffer.class)) {
         convertedValue = CharBuffer.wrap(textualValue);
      }
      else {
         Object valueFromText = newInstanceUsingPublicConstructorIfAvailable(returnType, STRING, textualValue);

         if (valueFromText != null) {
            convertedValue = valueFromText;
         }
      }

      return convertedValue;
   }

   private void addCharSequence(@Nonnull CharSequence textualValue) {
      Object convertedValue = getCharSequence(textualValue);
      addReturnValue(convertedValue);
   }

   @Nonnull
   private Object getPrimitiveValue() {
      Class<?> primitiveType = AutoBoxing.getPrimitiveType(valueToReturn.getClass());

      if (primitiveType != null) {
         Class<?>[] parameterType = {primitiveType};
         Object convertedValue = newInstanceUsingPublicConstructorIfAvailable(returnType, parameterType, valueToReturn);

         if (convertedValue == null) {
            convertedValue = invokePublicIfAvailable(returnType, null, "valueOf", parameterType, valueToReturn);
         }

         if (convertedValue != null) {
            return convertedValue;
         }
      }

      throw newIncompatibleTypesException();
   }

   private void addPrimitiveValue() {
      Object convertedValue = getPrimitiveValue();
      addReturnValue(convertedValue);
   }

   @Nonnull
   private Object getPrimitiveValueConvertingAsNeeded(@Nonnull Class<?> targetType) {
      Object convertedValue = null;

      if (valueToReturn instanceof Number) {
         convertedValue = convertFromNumber(targetType, (Number) valueToReturn);
      }
      else if (valueToReturn instanceof Character) {
         convertedValue = convertFromChar(targetType, (Character) valueToReturn);
      }

      if (convertedValue == null) {
         throw newIncompatibleTypesException();
      }

      return convertedValue;
   }

   private void addPrimitiveValueConvertingAsNeeded(@Nonnull Class<?> targetType) {
      Object convertedValue = getPrimitiveValueConvertingAsNeeded(targetType);
      addReturnValue(convertedValue);
   }

   @Nullable
   private static Object convertFromNumber(@Nonnull Class<?> targetType, @Nonnull Number number) {
      if (targetType == Integer.class) {
         return number.intValue();
      }

      if (targetType == Short.class) {
         return number.shortValue();
      }

      if (targetType == Long.class) {
         return number.longValue();
      }

      if (targetType == Byte.class) {
         return number.byteValue();
      }

      if (targetType == Double.class) {
         return number.doubleValue();
      }

      if (targetType == Float.class) {
         return number.floatValue();
      }

      if (targetType == Character.class) {
         return (char) number.intValue();
      }

      return null;
   }

   @Nullable
   private static Object convertFromChar(@Nonnull Class<?> targetType, char c) {
      if (targetType == Integer.class) {
         return (int) c;
      }

      if (targetType == Short.class) {
         return (short) c;
      }

      if (targetType == Long.class) {
         return (long) c;
      }

      if (targetType == Byte.class) {
         //noinspection NumericCastThatLosesPrecision
         return (byte) c;
      }

      if (targetType == Double.class) {
         return (double) c;
      }

      if (targetType == Float.class) {
         return (float) c;
      }

      return null;
   }
}
