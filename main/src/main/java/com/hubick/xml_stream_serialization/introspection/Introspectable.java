/*
 * Copyright 2017-2020 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU AFFERO GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, plus additional permissions, a copy of which you should have
 * received in the file LICENSE.txt.
 */

package com.hubick.xml_stream_serialization.introspection;

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

import javax.xml.*;
import javax.xml.stream.*;

import org.eclipse.jdt.annotation.*;

import com.hubick.xml_stream_serialization.xml.*;


/**
 * A lightweight interface for retrieving an object's property values, with built-in support for
 * {@linkplain XMLSerializable XML serialization}.
 */
@NonNullByDefault
public interface Introspectable extends XMLSerializable {

  /**
   * Retrieve the {@link Info} containing the property values exposed by this object.
   * 
   * @return The {@link Info} containing the property values exposed by this object.
   */
  public Info<?> introspect();

  @Override
  public default void writeXML(final XMLStreamWriter streamWriter, final @Nullable URI parentNamespace) throws XMLStreamException {
    introspect().writeXML(streamWriter, parentNamespace);
    return;
  }

  /**
   * A type-safe utility method for {@linkplain #introspect() introspecting} an object.
   * 
   * @param <I> The type of object being introspected.
   * @param introspectableClass The {@link Class} of object being introspected.
   * @param introspectable The {@link Introspectable} object.
   * @return The introspection {@link Info}.
   */
  @SuppressWarnings("unchecked")
  public static <I extends Introspectable> Info<I> introspect(final Class<I> introspectableClass, final I introspectable) {
    final Info<?> info = introspectable.introspect();
    if (!introspectableClass.isAssignableFrom(info.getType())) throw new ClassCastException();
    return (Info<I>)info;
  }

  /**
   * A map of {@link Property} values exposed by an {@link Introspectable} object.
   *
   * @param <I> The type of {@link Introspectable} object which generated this information.
   */
  public static final class Info<I extends Introspectable> extends AbstractMap<String,Info.Property<?,?>> implements Serializable, Map<String,Info.Property<?,?>>, XMLSerializable {
    private final Class<I> type;
    private final @Nullable URI namespace;
    private final boolean lateBound;
    private final Map<String,Property<?,?>> props;

    private Info(final Class<I> type, final @Nullable URI namespace, final boolean lateBound, final Map<String,Property<?,?>> props) {
      this.type = Objects.requireNonNull(type);
      this.namespace = namespace;
      this.lateBound = lateBound;
      this.props = Collections.unmodifiableMap(Objects.requireNonNull(props));
      return;
    }

    /**
     * Get the {@link Class} of {@link Introspectable} object which generated this information.
     * 
     * @return The {@link Class} of {@link Introspectable} object which generated this information.
     */
    public Class<I> getType() {
      return type;
    }

    /**
     * Get an optional {@link URI} representing the namespace used for this information.
     * 
     * @return An optional {@link URI} representing the namespace used for this information.
     */
    public @Nullable URI getNamespace() {
      return namespace;
    }

    @Override
    public Set<Map.Entry<String,Property<?,?>>> entrySet() {
      return props.entrySet();
    }

    /**
     * Perform a type-safe up cast of the information about this type.
     * 
     * @param <T> The superclass of the type which generated this information.
     * @param type The super-{@link Class} of the type which generated this information.
     * @return This information instance casted to appear as though it originated from a superclass of the object it
     * came from.
     */
    @SuppressWarnings("unchecked")
    public <T extends Introspectable> Info<T> superCast(final Class<T> type) { // Too bad Java doesn't allow multiple type bounds on methods, or this would be: <T extends Introspectable & T super I>
      if (!type.isAssignableFrom(this.type)) throw new ClassCastException();
      return (Info<T>)this;
    }

    /**
     * Create a {@link Builder} to extend the {@link Property} values contained within this object.
     * 
     * @return A {@link Builder} pre-populated with the {@link Property} values contained within this object.
     */
    public Builder<I> build() {
      return new Builder<>(type, namespace, lateBound, props);
    }

    /**
     * Filter the {@link Stream} of {@link Property} values for those of the specified type.
     * 
     * @param <P> The type of {@link Property} desired.
     * @param props A {@link Stream} of {@link Property} values to be filtered.
     * @param propClass The {@link Class} of {@link Property} desired.
     * @return A {@link Stream} only containing {@link Property} values of the specified type.
     */
    public static final <@NonNull P extends Property<?,?>> Stream<Map.Entry<String,P>> filter(final Stream<Map.Entry<String,Property<?,?>>> props, final Class<P> propClass) {
      return props.filter((e) -> propClass.isInstance(e.getValue())).<Map.Entry<String,P>> map((e) -> new AbstractMap.SimpleImmutableEntry<>(e.getKey(), Objects.requireNonNull(propClass.cast(e.getValue()))));
    }

    /**
     * Get the non-{@linkplain Property#isEmpty() empty} {@link Property} values contained within this object.
     * 
     * @return A {@link Map} of non-{@linkplain Property#isEmpty() empty} {@link Property} values.
     */
    public Map<String,Property<?,?>> getValues() {
      return Collections.unmodifiableMap(entrySet().stream()
          .filter((entry) -> !entry.getValue().isEmpty())
          .collect(Collectors.<Map.Entry<String,Property<?,?>>,String,Property<?,?>,LinkedHashMap<String,Property<?,?>>> toMap(Map.Entry::getKey, Map.Entry::getValue, (u, v) -> u, LinkedHashMap::new)));
    }

    /**
     * Get the non-{@linkplain Attr#isEmpty() empty} {@link Attr} values contained within this object.
     * 
     * @return A {@link Map} of non-{@linkplain Attr#isEmpty() empty} {@link Attr} values.
     */
    public Map<String,Attr<?>> getAttrs() {
      return Collections.unmodifiableMap(filter(entrySet().stream(), Attr.WILDCARD_CLASS)
          .filter((entry) -> !entry.getValue().isEmpty())
          .collect(Collectors.<Map.Entry<String,Attr<?>>,String,Attr<?>,LinkedHashMap<String,Attr<?>>> toMap(Map.Entry::getKey, Map.Entry::getValue, (u, v) -> u, LinkedHashMap::new)));
    }

    /**
     * Get the non-{@linkplain Child#isEmpty() empty} {@link Child} values contained within this object.
     * 
     * @return A {@link Map} of non-{@linkplain Child#isEmpty() empty} {@link Child} values.
     */
    public Map<String,Child<?,?>> getChildren() {
      return Collections.unmodifiableMap(filter(entrySet().stream(), Child.WILDCARD_CLASS)
          .filter((entry) -> !entry.getValue().isEmpty())
          .collect(Collectors.<Map.Entry<String,Child<?,?>>,String,Child<?,?>,LinkedHashMap<String,Child<?,?>>> toMap(Map.Entry::getKey, Map.Entry::getValue, (u, v) -> u, LinkedHashMap::new)));
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, namespace, lateBound, props);
    }

    @Override
    public boolean equals(final @Nullable Object other) {
      return Optional.ofNullable(other).filter(Info.class::isInstance)
          .map(Info.class::cast)
          .filter((i) -> type.equals(i.type))
          .filter((i) -> Objects.equals(namespace, i.namespace))
          .filter((i) -> lateBound == i.lateBound)
          .filter((i) -> props.equals(i.props))
          .isPresent();
    }

    @Override
    public String toString() {
      return getValues().toString();
    }

    @Override
    public void writeXML(final XMLStreamWriter streamWriter, final @Nullable URI parentNamespace) throws XMLStreamException {
      final String nsString = Optional.ofNullable(getNamespace()).map(URI::toString).orElse(XMLConstants.NULL_NS_URI);

      final Map<String,Info.Child<?,?>> children = getChildren();
      if (!children.isEmpty()) {
        streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, getType().getSimpleName(), nsString);
      } else {
        streamWriter.writeEmptyElement(XMLConstants.DEFAULT_NS_PREFIX, getType().getSimpleName(), nsString);
      }

      for (final Map.Entry<String,Info.Attr<?>> attr : getAttrs().entrySet().stream().filter((attr) -> !attr.getValue().isDefaultValue()).collect(Collectors.toList())) {
        streamWriter.writeAttribute(attr.getKey(), attr.getValue().get().get());
      }

      if (!Optional.ofNullable(getNamespace()).equals(Optional.ofNullable(parentNamespace))) streamWriter.writeDefaultNamespace(nsString);

      if (!children.isEmpty()) {
        for (final Map.Entry<String,Info.Child<?,?>> child : children.entrySet()) {

          final boolean propElementRequired; // We default to generating "shallow" XML, where child Collection/Map values are output as direct child elements, and only generate a "grouping element" for the prop when necessary.
          final Function<Info.Child<?,?>,String> valueNameFunction = (c) -> Info.ComplexCollection.cast(c).map(Info.Property::getValueType).map(Class::getSimpleName).orElse(Info.ComplexMap.cast(c).map(Info.Property::getValueType).map(Class::getSimpleName).orElse(c.getValueName()));
          if (child.getValue().getValueTypeExtensions()) {
            propElementRequired = true; // If the values consist of various subclasses, we need to group them, as we don't know what the names will be and if those will collide.
          } else if (child.getKey().equals(valueNameFunction.apply(child.getValue()))) {
            propElementRequired = false; // We'll never nest two elements with the same name (common path when prop is a single complex child wrapped in a collection).
          } else {
            propElementRequired = filter(entrySet().stream(), Child.WILDCARD_CLASS).<Info.Child<?,?>> map(Map.Entry::getValue)
                .filter((someChild) -> someChild != child.getValue())
                .filter((otherChild) -> !otherChild.getValueTypeExtensions())
                .filter((otherChild) -> valueNameFunction.apply(otherChild).equals(valueNameFunction.apply(child.getValue())))
                .findAny()
                .isPresent(); // Will the value name for this prop collide with that of any other prop?
          }

          if (propElementRequired) streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, child.getKey(), nsString);

          if (Info.PrimitiveCollection.cast(child.getValue()).isPresent()) {

            final Info.PrimitiveCollection<?> primitiveCollection = Info.PrimitiveCollection.cast(child.getValue()).get();
            final Iterator<? extends @Nullable String> iter = primitiveCollection.get().iterator();
            while (iter.hasNext()) {
              final @Nullable String value = iter.next();
              if (value != null) {
                streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, child.getValue().getValueName(), nsString);
                streamWriter.writeCharacters(value);
                streamWriter.writeEndElement();
              } else {
                streamWriter.writeEmptyElement(XMLConstants.DEFAULT_NS_PREFIX, child.getValue().getValueName(), nsString);
              }
            }

          } else if (Info.ComplexCollection.cast(child.getValue()).isPresent()) {

            final Info.ComplexCollection<?> complexCollection = Info.ComplexCollection.cast(child.getValue()).get();
            final Iterator<? extends @Nullable Info<?>> iter = complexCollection.get().iterator();
            while (iter.hasNext()) {
              final @Nullable Info<?> value = iter.next();
              if (value != null) value.writeXML(streamWriter, getNamespace());
            }

          } else if (Info.PrimitiveMap.cast(child.getValue()).isPresent()) {

            final Info.PrimitiveMap<?,?> primitiveMap = Info.PrimitiveMap.cast(child.getValue()).get();
            final Iterator<? extends Map.Entry<? extends @Nullable String,? extends @Nullable String>> iter = primitiveMap.get().iterator();
            while (iter.hasNext()) {
              final Map.Entry<? extends @Nullable String,? extends @Nullable String> entry = iter.next();
              final @Nullable String key = entry.getKey();
              final @Nullable String value = entry.getValue();
              if (value != null) {
                streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, child.getValue().getValueName(), nsString);
              } else {
                streamWriter.writeEmptyElement(XMLConstants.DEFAULT_NS_PREFIX, child.getValue().getValueName(), nsString);
              }
              if (key != null) streamWriter.writeAttribute(primitiveMap.getKeyName(), key);
              if (value != null) {
                streamWriter.writeCharacters(value);
                streamWriter.writeEndElement();
              }
            }

          } else if (Info.ComplexMap.cast(child.getValue()).isPresent()) {

            final Info.ComplexMap<?,?> complexMap = Info.ComplexMap.cast(child.getValue()).get();
            final Iterator<? extends Map.Entry<? extends @Nullable String,? extends @Nullable Info<?>>> iter = complexMap.get().iterator();
            while (iter.hasNext()) {
              final Map.Entry<? extends @Nullable String,? extends @Nullable Info<?>> entry = iter.next();
              final @Nullable String key = entry.getKey();
              final @Nullable Info<?> value = entry.getValue();
              if (value != null) {
                streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, child.getValue().getValueName(), nsString);
              } else {
                streamWriter.writeEmptyElement(XMLConstants.DEFAULT_NS_PREFIX, child.getValue().getValueName(), nsString);
              }
              if (key != null) streamWriter.writeAttribute(complexMap.getKeyName(), key);
              if (value != null) {
                value.writeXML(streamWriter, getNamespace());
                streamWriter.writeEndElement();
              }
            }

          } // ComplexMap child

          if (propElementRequired) streamWriter.writeEndElement();
        } // for child

        streamWriter.writeEndElement();
      } // !children.isEmpty()
      return;
    }

    /**
     * The abstract base class for representing any property exposed by an {@link Introspectable} object.
     *
     * @param <V> The type of value this property contains.
     * @param <C> The type of container used to expose this property's contents.
     */
    public abstract static class Property<V,@NonNull C> implements Supplier<C>, Serializable {
      protected final Class<?> declaringClass;
      protected final Class<V> valueType;
      protected final boolean valueTypeExtensions;
      protected final C value;

      private Property(final Class<?> declaringClass, final Class<V> valueType, final boolean valueTypeExtensions, final C value) {
        this.declaringClass = Objects.requireNonNull(declaringClass);
        this.valueType = Objects.requireNonNull(valueType);
        this.valueTypeExtensions = valueTypeExtensions;
        this.value = Objects.requireNonNull(value);
        return;
      }

      /**
       * Get the {@link Class} which declares this property.
       * 
       * @return The {@link Class} which declares this property.
       */
      public final Class<?> getDeclaringClass() {
        return declaringClass;
      }

      /**
       * Get the {@link Class} of value this property contains.
       * 
       * @return The {@link Class} of value this property contains.
       */
      public final Class<V> getValueType() {
        return valueType;
      }

      /**
       * Is every value exposed by this property guaranteed to be of the declared {@linkplain #getValueType() value
       * type}, or are various extensions/subclasses of that type also allowed?
       * 
       * @return If value type extensions are allowed.
       */
      public final boolean getValueTypeExtensions() {
        return valueTypeExtensions;
      }

      @Override
      public C get() {
        return value;
      }

      /**
       * Is there no value present within this property?
       * 
       * @return A boolean value indicating if this property is empty.
       */
      public abstract boolean isEmpty();

      @Override
      public int hashCode() {
        return Objects.hash(declaringClass, valueType, valueTypeExtensions, value);
      }

      @Override
      public boolean equals(final @Nullable Object other) {
        return Optional.ofNullable(other).filter(Property.class::isInstance)
            .map(Property.class::cast)
            .filter((p) -> declaringClass.equals(p.declaringClass))
            .filter((p) -> valueType.equals(p.valueType))
            .filter((p) -> valueTypeExtensions == p.valueTypeExtensions)
            .filter((p) -> value.equals(p.value))
            .isPresent();
      }

      @Override
      public String toString() {
        return get().toString();
      }

    } // Info.Property

    /**
     * A {@link Property} with an unstructured/single value.
     *
     * @param <V> The type of value this attribute contains.
     */
    public static final class Attr<V> extends Property<V,Optional<String>> {
      @SuppressWarnings("unchecked")
      static final Class<Attr<?>> WILDCARD_CLASS = (Class<Attr<?>>)(Object)Attr.class;
      protected final boolean isDefaultValue;

      private Attr(final Class<?> declaringClass, final Class<V> valueType, final boolean valueTypeExtensions, @Nullable String value, final boolean isDefaultValue) {
        super(declaringClass, valueType, valueTypeExtensions, Optional.ofNullable(value));
        this.isDefaultValue = isDefaultValue;
        return;
      }

      @Override
      public boolean isEmpty() {
        return !get().isPresent();
      }

      /**
       * Is the {@linkplain #get() value} contained within this attribute equal to it's "default" value?
       * 
       * @return <code>true</code> if this attribute's value is equal to it's default.
       */
      public boolean isDefaultValue() {
        return isDefaultValue;
      }

      @Override
      public String toString() {
        return String.valueOf(value.isPresent() ? value.get() : null);
      }

    } // Info.Attr

    /**
     * A {@link Property} with a complex or multi-valued structure.
     *
     * @param <V> The type of value this child contains.
     * @param <C> The type of container used to expose this property's contents.
     */
    public static abstract class Child<V,@NonNull C extends Iterable<?>> extends Property<V,C> {
      @SuppressWarnings("unchecked")
      static final Class<Child<?,?>> WILDCARD_CLASS = (Class<Child<?,?>>)(Object)Child.class;
      protected final String valueName;

      protected Child(final Class<?> declaringClass, final Class<V> valueType, final boolean valueTypeExtensions, final String valueName, final C values) {
        super(declaringClass, valueType, valueTypeExtensions, values);
        this.valueName = Objects.requireNonNull(valueName);
        return;
      }

      /**
       * Get the name used to describe an individual value.
       * 
       * @return The name used to describe an <em>individual</em> value.
       */
      public String getValueName() {
        return valueName;
      }

      @Override
      public final boolean isEmpty() {
        return !get().iterator().hasNext();
      }

    } // Info.Child

    /**
     * A {@link Property} which is a {@link Child} type restricted to containing an ordered sequence of values (either
     * structured or unstructured).
     *
     * @param <V> The type of value this collection contains.
     * @param <C> The type of container used to expose this property's contents.
     */
    public static abstract class CollectionChild<V,@NonNull C extends Iterable<?>> extends Child<V,C> {
      @SuppressWarnings("unchecked")
      static final Class<CollectionChild<?,?>> WILDCARD_CLASS = (Class<CollectionChild<?,?>>)(Object)CollectionChild.class;

      private CollectionChild(final Class<?> declaringClass, final Class<V> valueType, final boolean valueTypeExtensions, final String valueName, final C values) {
        super(declaringClass, valueType, valueTypeExtensions, valueName, values);
        return;
      }

      /**
       * If the supplied {@link Property} is a {@link CollectionChild}, return it {@link Class#cast(Object) cast} as
       * such.
       * 
       * @param prop The {@link Property} which is to have it's type evaluated.
       * @return An {@link Optional} instance containing the supplied property if it was a {@link CollectionChild}, or
       * {@linkplain Optional#empty() empty} if it wasn't.
       */
      public static final Optional<CollectionChild<?,?>> castCollection(final Property<?,?> prop) {
        return Optional.of(prop).filter(CollectionChild.class::isInstance).map(CollectionChild.class::cast);
      }

    } // Info.CollectionChild

    /**
     * A {@link Property} which is a {@link CollectionChild} type restricted to containing an ordered sequence of
     * primitive/unstructured values.
     *
     * @param <V> The type of value this collection contains.
     */
    public static final class PrimitiveCollection<V> extends CollectionChild<V,Iterable<? extends @Nullable String>> {
      @SuppressWarnings("unchecked")
      static final Class<PrimitiveCollection<?>> WILDCARD_CLASS = (Class<PrimitiveCollection<?>>)(Object)PrimitiveCollection.class;

      private PrimitiveCollection(final Class<?> declaringClass, final Class<V> valueType, final boolean valueTypeExtensions, final String valueName, final @Nullable Iterable<? extends @Nullable String> values) {
        super(declaringClass, valueType, valueTypeExtensions, valueName, (values != null) ? values : Collections.emptyList());
        return;
      }

      /**
       * If the supplied {@link Property} is a {@link PrimitiveCollection}, return it {@link Class#cast(Object) cast} as
       * such.
       * 
       * @param prop The {@link Property} which is to have it's type evaluated.
       * @return An {@link Optional} instance containing the supplied property if it was a {@link PrimitiveCollection},
       * or {@linkplain Optional#empty() empty} if it wasn't.
       */
      public static final Optional<PrimitiveCollection<?>> cast(final Property<?,?> prop) {
        return Optional.of(prop).filter(PrimitiveCollection.class::isInstance).map(PrimitiveCollection.class::cast);
      }

    } // Info.PrimitiveCollection

    /**
     * A {@link Property} which is a {@link CollectionChild} type restricted to containing an ordered sequence of
     * complex/structured values.
     *
     * @param <V> The type of value this collection contains.
     */
    public static final class ComplexCollection<V extends Introspectable> extends CollectionChild<V,Iterable<? extends @Nullable Info<V>>> {
      @SuppressWarnings("unchecked")
      static final Class<ComplexCollection<?>> WILDCARD_CLASS = (Class<ComplexCollection<?>>)(Object)ComplexCollection.class;

      private ComplexCollection(final Class<?> declaringClass, final Class<V> valueType, final boolean valueTypeExtensions, final String valueName, final @Nullable Iterable<? extends @Nullable Info<V>> values) {
        super(declaringClass, valueType, valueTypeExtensions, valueName, (values != null) ? values : Collections.emptyList());
        return;
      }

      /**
       * If the supplied {@link Property} is a {@link ComplexCollection}, return it {@link Class#cast(Object) cast} as
       * such.
       * 
       * @param prop The {@link Property} which is to have it's type evaluated.
       * @return An {@link Optional} instance containing the supplied property if it was a {@link ComplexCollection}, or
       * {@linkplain Optional#empty() empty} if it wasn't.
       */
      public static final Optional<ComplexCollection<?>> cast(final Property<?,?> prop) {
        return Optional.of(prop).filter(ComplexCollection.class::isInstance).map(ComplexCollection.class::cast);
      }

    } // Info.ComplexCollection

    /**
     * A {@link Property} which is a {@link Child} type restricted to containing an ordered sequence of key to value
     * mappings (either structured or unstructured).
     *
     * @param <K> The type of key this map contains.
     * @param <V> The type of value this map contains.
     * @param <C> The type of container used to expose this property's contents.
     */
    public static abstract class MapChild<K,V,@NonNull C extends Iterable<? extends Map.Entry<? extends @Nullable String,?>>> extends Child<V,C> {
      @SuppressWarnings("unchecked")
      static final Class<MapChild<?,?,?>> WILDCARD_CLASS = (Class<MapChild<?,?,?>>)(Object)MapChild.class;
      protected final Class<K> keyType;
      protected final boolean keyTypeExtensions;
      protected final String keyName;

      private MapChild(final Class<?> declaringClass, final Class<K> keyType, final boolean keyTypeExtensions, final String keyName, final Class<V> valueType, final boolean valueTypeExtensions, final String valueName, final C values) {
        super(declaringClass, valueType, valueTypeExtensions, valueName, values);
        this.keyType = Objects.requireNonNull(keyType);
        this.keyTypeExtensions = keyTypeExtensions;
        this.keyName = Objects.requireNonNull(keyName);
        return;
      }

      /**
       * Get the {@link Class} of key this property contains.
       * 
       * @return The {@link Class} of key this property contains.
       */
      public Class<K> getKeyType() {
        return keyType;
      }

      /**
       * Is every key exposed by this property guaranteed to be of the declared {@linkplain #getKeyType() key type}, or
       * are various extensions/subclasses of that type also allowed?
       * 
       * @return If key type extensions are allowed.
       */
      public boolean getKeyTypeExtensions() {
        return keyTypeExtensions;
      }

      /**
       * Get the name used to describe an individual key.
       * 
       * @return The name used to describe an <em>individual</em> key.
       */
      public String getKeyName() {
        return keyName;
      }

      /**
       * If the supplied {@link Property} is a {@link MapChild}, return it {@link Class#cast(Object) cast} as such.
       * 
       * @param prop The {@link Property} which is to have it's type evaluated.
       * @return An {@link Optional} instance containing the supplied property if it was a {@link MapChild}, or
       * {@linkplain Optional#empty() empty} if it wasn't.
       */
      public static final Optional<MapChild<?,?,?>> castMap(final Property<?,?> prop) {
        return Optional.of(prop).filter(MapChild.class::isInstance).map(MapChild.class::cast);
      }

    } // Info.MapChild

    /**
     * A {@link Property} which is a {@link MapChild} type restricted to containing an ordered sequence of key to
     * primitive/unstructured value mappings.
     *
     * @param <K> The type of key this map contains.
     * @param <V> The type of value this map contains.
     */
    public static final class PrimitiveMap<K,V> extends MapChild<K,V,Iterable<? extends Map.Entry<? extends @Nullable String,? extends @Nullable String>>> {
      @SuppressWarnings("unchecked")
      static final Class<PrimitiveMap<?,?>> WILDCARD_CLASS = (Class<PrimitiveMap<?,?>>)(Object)PrimitiveMap.class;

      private PrimitiveMap(final Class<?> declaringClass, final Class<K> keyType, final boolean keyTypeExtensions, final String keyName, final Class<V> valueType, final boolean valueTypeExtensions, final String valueName, final @Nullable Iterable<? extends Map.Entry<? extends @Nullable String,? extends @Nullable String>> values) {
        super(declaringClass, keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, valueName, (values != null) ? values : Collections.emptyList());
        return;
      }

      /**
       * If the supplied {@link Property} is a {@link PrimitiveMap}, return it {@link Class#cast(Object) cast} as such.
       * 
       * @param prop The {@link Property} which is to have it's type evaluated.
       * @return An {@link Optional} instance containing the supplied property if it was a {@link PrimitiveMap}, or
       * {@linkplain Optional#empty() empty} if it wasn't.
       */
      public static final Optional<PrimitiveMap<?,?>> cast(final Property<?,?> prop) {
        return Optional.of(prop).filter(PrimitiveMap.class::isInstance).map(PrimitiveMap.class::cast);
      }

    } // Info.PrimitiveMap

    /**
     * A {@link Property} which is a {@link MapChild} type restricted to containing an ordered sequence of key to
     * complex/structured value mappings.
     *
     * @param <K> The type of key this map contains.
     * @param <V> The type of value this map contains.
     */
    public static final class ComplexMap<K,V extends Introspectable> extends MapChild<K,V,Iterable<? extends Map.Entry<? extends @Nullable String,? extends @Nullable Info<V>>>> {
      @SuppressWarnings("unchecked")
      static final Class<ComplexMap<?,?>> WILDCARD_CLASS = (Class<ComplexMap<?,?>>)(Object)ComplexMap.class;

      private ComplexMap(final Class<?> declaringClass, final Class<K> keyType, final boolean keyTypeExtensions, final String keyName, final Class<V> valueType, final boolean valueTypeExtensions, final String valueName, final @Nullable Iterable<? extends Map.Entry<? extends @Nullable String,? extends @Nullable Info<V>>> values) {
        super(declaringClass, keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, valueName, (values != null) ? values : Collections.emptyList());
        return;
      }

      /**
       * If the supplied {@link Property} is a {@link ComplexMap}, return it {@link Class#cast(Object) cast} as such.
       * 
       * @param prop The {@link Property} which is to have it's type evaluated.
       * @return An {@link Optional} instance containing the supplied property if it was a {@link ComplexMap}, or
       * {@linkplain Optional#empty() empty} if it wasn't.
       */
      public static final Optional<ComplexMap<?,?>> cast(final Property<?,?> prop) {
        return Optional.of(prop).filter(ComplexMap.class::isInstance).map(ComplexMap.class::cast);
      }

    } // Info.ComplexMap

    /**
     * Used by {@link Introspectable} objects to compile their {@link Property} values into an {@link Info} set.
     *
     * @param <I> The type of {@link Introspectable} object this builder is compiling {@link Property} values for.
     */
    public static final class Builder<I extends Introspectable> {
      private final Class<I> type;
      private final @Nullable URI namespace;
      private final boolean lateBound;
      private final Map<String,Property<?,?>> props;

      private Builder(final Class<I> type, final @Nullable URI namespace, final boolean lateBound, final @Nullable Map<String,Property<?,?>> props) {
        this.type = Objects.requireNonNull(type);
        this.namespace = namespace;
        this.lateBound = lateBound;
        this.props = Collections.synchronizedMap((props != null) ? new LinkedHashMap<>(props) : new LinkedHashMap<>());
        return;
      }

      /**
       * Construct a new {@link Info} <code>Builder</code>.
       * 
       * @param type The {@link Class} of {@link Introspectable} object this builder is compiling {@link Property}
       * values for.
       * @param namespace The {@link Info#getNamespace() namespace} of the {@link Info}.
       * @param lateBound Should {@linkplain Introspectable#introspect() introspection} of complex
       * ({@link Introspectable}) properties <em>not</em> be performed immediately as they are added to this builder?
       * The <em>default</em> immediate binding (<code>lateBound</code> == <code>false</code>) results in an
       * {@link Info} instance which represents an immutable atomic snapshot of the entire object tree being
       * introspected. Late binding allows for indefinitely large objects to stream their child values, introspecting
       * each child one by one as the parent {@link Info} itself is iterated through.
       */
      public Builder(final Class<I> type, final @Nullable URI namespace, final boolean lateBound) {
        this(type, namespace, lateBound, null);
        return;
      }

      /**
       * Return a Builder for the provided <code>type</code> which contains a copy of all the properties this one has.
       * 
       * @param <T> The type of {@link Introspectable} object the new builder will be compiling {@link Property} values
       * for.
       * @param type The {@link Class} of {@link Introspectable} object the new builder will be compiling
       * {@link Property} values for.
       * @return A copy of this Builder if the provided <code>type</code> is the same as this one, or a new Builder
       * instance of the provided type.
       */
      @SuppressWarnings("unchecked")
      public <T extends Introspectable> Builder<T> type(final Class<T> type) {
        return this.type.equals(type) ? (Builder<T>)this : new Builder<T>(type, namespace, lateBound, props);
      }

      private Builder<I> put(final String name, final Property<?,?> prop) {
        props.put(name, prop);
        return this;
      }

      /**
       * Remove the named {@link Property} from this builder.
       * 
       * @param propName The name of the {@link Property} to remove.
       * @return The {@link Builder} this method was invoked on.
       * @throws NoSuchElementException If this builder contains no <code>propName</code> {@link Property}.
       */
      public Builder<I> remove(final String propName) throws NoSuchElementException {
        Optional.ofNullable(props.remove(propName)).orElseThrow(() -> new NoSuchElementException(getClass().getSimpleName() + " for type '" + type.getName() + "' attempted to remove non-existing '" + propName + "' property"));
        return this;
      }

      /**
       * Remove the named {@link Property} from this builder.
       * 
       * @param propName The name of the {@link Property} to remove.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> removeOpt(final String propName) {
        props.remove(propName);
        return this;
      }

      /**
       * Populate this builder with all {@link Property}'s from the supplied {@link Info}.
       * 
       * @param info The info to populate this builder with.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> addAll(final Info<?> info) {
        props.putAll(info.props);
        return this;
      }

      private <T,R> Iterable<? extends R> mapValues(final Iterable<? extends T> iterable, final Function<? super T,? extends R> mapper) {
        if (lateBound) {
          return new Iterable<R>() {

            @Override
            public Iterator<R> iterator() {
              return new Iterator<R>() {
                protected final Iterator<? extends T> iter = iterable.iterator();

                @Override
                public boolean hasNext() {
                  return iter.hasNext();
                }

                @Override
                public R next() {
                  return mapper.apply(iter.next());
                }

              };

            }

          };
        }
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterable.iterator(), Spliterator.ORDERED), false).map(mapper).collect(Collectors.toList());
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property}.
       * 
       * @param <V> The type of attribute <code>value</code> being added.
       * @param valueType The {@link Class} of attribute <code>value</code> being added.
       * @param valueTypeExtensions Does the <code>valueType</code> include
       * {@linkplain Property#getValueTypeExtensions() extensions}?
       * @param propName The name which uniquely identifies this property.
       * @param defaultValue What is the default value for this attribute?
       * @param value The attribute value being added.
       * @param valueToString A {@link Function} to create a {@link String} representation of the supplied
       * <code>value</code>.
       * @return The {@link Builder} this method was invoked on.
       */
      public <V> Builder<I> attr(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final @Nullable V defaultValue, final @Nullable V value, final Function<? super V,? extends String> valueToString) {
        return put(propName, new Attr<V>(type, valueType, valueTypeExtensions, (value != null) ? valueToString.apply(value) : null, (defaultValue != null) && (defaultValue.equals(value))));
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property}.
       * 
       * @param <V> The type of attribute <code>value</code> being added.
       * @param valueType The {@link Class} of attribute <code>value</code> being added.
       * @param valueTypeExtensions Does the <code>valueType</code> include
       * {@linkplain Property#getValueTypeExtensions() extensions}?
       * @param propName The name which uniquely identifies this property.
       * @param defaultValue What is the default value for this attribute?
       * @param value The attribute value being added.
       * @param valueToString A {@link Function} to create a {@link String} representation of the supplied
       * <code>value</code>.
       * @return The {@link Builder} this method was invoked on.
       */
      public <V> Builder<I> attr(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final @Nullable V defaultValue, final Optional<? extends V> value, final Function<? super V,? extends String> valueToString) {
        return attr(valueType, valueTypeExtensions, propName, defaultValue, value.isPresent() ? value.get() : null, valueToString);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property}.
       * 
       * @param <V> The type of attribute <code>value</code> being added.
       * @param valueType The {@link Class} of attribute <code>value</code> being added.
       * @param valueTypeExtensions Does the <code>valueType</code> include
       * {@linkplain Property#getValueTypeExtensions() extensions}?
       * @param propName The name which uniquely identifies this property.
       * @param defaultValue What is the default value for this attribute?
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public <V> Builder<I> attr(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final @Nullable V defaultValue, final @Nullable V value) {
        return attr(valueType, valueTypeExtensions, propName, defaultValue, value, Object::toString);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property}.
       * 
       * @param <V> The type of attribute <code>value</code> being added.
       * @param valueType The {@link Class} of attribute <code>value</code> being added.
       * @param valueTypeExtensions Does the <code>valueType</code> include
       * {@linkplain Property#getValueTypeExtensions() extensions}?
       * @param propName The name which uniquely identifies this property.
       * @param defaultValue What is the default value for this attribute?
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public <V> Builder<I> attr(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final @Nullable V defaultValue, final Optional<? extends V> value) {
        return attr(valueType, valueTypeExtensions, propName, defaultValue, value.isPresent() ? value.get() : null);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link String} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable String value) {
        return attr(String.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link String} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final Optional<? extends String> value) {
        return attr(String.class, false, propName, null, value.isPresent() ? value.get() : null);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link Boolean} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable Boolean value) {
        return attr(Boolean.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link Integer} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable Integer value) {
        return attr(Integer.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link Long} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable Long value) {
        return attr(Long.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link Double} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable Double value) {
        return attr(Double.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link Float} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable Float value) {
        return attr(Float.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link Short} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable Short value) {
        return attr(Short.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link UUID} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable UUID value) {
        return attr(UUID.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link URI} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable URI value) {
        return attr(URI.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link URL} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable URL value) {
        return attr(URL.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link Date} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable Date value) {
        return attr(Date.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link Calendar} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable Calendar value) {
        return attr(Calendar.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link Instant} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable Instant value) {
        return attr(Instant.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link Duration} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable Duration value) {
        return attr(Duration.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link LocalDate} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable LocalDate value) {
        return attr(LocalDate.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link LocalTime} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable LocalTime value) {
        return attr(LocalTime.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link LocalDateTime} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable LocalDateTime value) {
        return attr(LocalDateTime.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link ZonedDateTime} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable ZonedDateTime value) {
        return attr(ZonedDateTime.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link OffsetDateTime} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable OffsetDateTime value) {
        return attr(OffsetDateTime.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link OffsetTime} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable OffsetTime value) {
        return attr(OffsetTime.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link Year} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable Year value) {
        return attr(Year.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link YearMonth} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable YearMonth value) {
        return attr(YearMonth.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link Month} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable Month value) {
        return attr(Month.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link MonthDay} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable MonthDay value) {
        return attr(MonthDay.class, false, propName, null, value);
      }

      /**
       * Add an {@linkplain Attr attribute} {@link Property} with a {@link DayOfWeek} value.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param value The attribute value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> attr(final String propName, final @Nullable DayOfWeek value) {
        return attr(DayOfWeek.class, false, propName, null, value);
      }

      /**
       * Add a {@link Property} with a {@linkplain PrimitiveCollection primitive collection} of values.
       * 
       * @param <V> The type of value this collection contains.
       * @param valueType The {@link Class} of value this collection contains.
       * @param valueTypeExtensions Does the <code>valueType</code> include
       * {@linkplain Property#getValueTypeExtensions() extensions}?
       * @param propName The name which uniquely identifies this property.
       * @param valueName The name used to describe an <em>individual</em> value.
       * @param values The child values being added.
       * @param valueToString A {@link Function} to create a {@link String} representation of the supplied
       * <code>values</code>.
       * @return The {@link Builder} this method was invoked on.
       */
      public <V> Builder<I> primitiveChild(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Iterable<? extends @Nullable V> values, final Function<? super V,? extends String> valueToString) {
        return put(propName, new PrimitiveCollection<V>(type, valueType, valueTypeExtensions, valueName, (values != null) ? mapValues(values, (value) -> (value != null) ? valueToString.apply(value) : null) : null));
      }

      /**
       * Add a {@link Property} with a {@linkplain PrimitiveCollection primitive collection} of values.
       * 
       * @param <V> The type of value this collection contains.
       * @param valueType The {@link Class} of value this collection contains.
       * @param valueTypeExtensions Does the <code>valueType</code> include
       * {@linkplain Property#getValueTypeExtensions() extensions}?
       * @param propName The name which uniquely identifies this property.
       * @param valueName The name used to describe an <em>individual</em> value.
       * @param values The child values being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public <V> Builder<I> primitiveChild(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Collection<? extends @Nullable V> values) {
        return primitiveChild(valueType, valueTypeExtensions, propName, valueName, values, Object::toString);
      }

      /**
       * Add a {@link Property} with {@linkplain PrimitiveCollection primitive collection} of {@link String} values.
       * 
       * @param propName The name which uniquely identifies this property.
       * @param valueName The name used to describe an <em>individual</em> value.
       * @param values The child values being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public Builder<I> primitiveChild(final String propName, final String valueName, final @Nullable Collection<? extends @Nullable String> values) {
        return primitiveChild(String.class, false, propName, valueName, values);
      }

      /**
       * Add a {@link Property} with a {@linkplain ComplexCollection complex collection} of values.
       * 
       * @param <V> The type of value this collection contains.
       * @param valueType The {@link Class} of value this collection contains.
       * @param valueTypeExtensions Does the <code>valueType</code> include
       * {@linkplain Property#getValueTypeExtensions() extensions}?
       * @param propName The name which uniquely identifies this property.
       * @param valueName The name used to describe an <em>individual</em> value.
       * @param values The child values being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public <V extends Introspectable> Builder<I> complexChild(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Iterable<? extends @Nullable V> values) {
        return put(propName, new ComplexCollection<V>(type, valueType, valueTypeExtensions, valueName, (values != null) ? mapValues(values, (value) -> (value != null) ? Introspectable.introspect(valueType, value) : null) : null));
      }

      /**
       * Add a {@link Property} with a {@linkplain ComplexCollection complex} value.
       * 
       * @param <V> The type of value.
       * @param valueType The {@link Class} of value.
       * @param valueTypeExtensions Does the <code>valueType</code> include
       * {@linkplain Property#getValueTypeExtensions() extensions}?
       * @param propName The name which uniquely identifies this property.
       * @param value The child value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public <V extends Introspectable> Builder<I> complexChild(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final @Nullable V value) {
        return complexChild(valueType, valueTypeExtensions, propName, propName, (value != null) ? Collections.singleton(value) : null);
      }

      /**
       * Add a {@link Property} with a {@linkplain ComplexCollection complex} value.
       * 
       * @param <V> The type of value.
       * @param valueType The {@link Class} of value.
       * @param valueTypeExtensions Does the <code>valueType</code> include
       * {@linkplain Property#getValueTypeExtensions() extensions}?
       * @param propName The name which uniquely identifies this property.
       * @param value The child value being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public <V extends Introspectable> Builder<I> complexChild(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final Optional<V> value) {
        return complexChild(valueType, valueTypeExtensions, propName, value.isPresent() ? value.get() : null);
      }

      /**
       * Add a {@link Property} with a {@linkplain PrimitiveMap primitive map} of values.
       * 
       * @param <K> The type of key this map contains.
       * @param <V> The type of value this map contains.
       * @param keyType The {@link Class} of key this map contains.
       * @param keyTypeExtensions Does the <code>keyType</code> include {@linkplain MapChild#getKeyTypeExtensions()
       * extensions}?
       * @param keyName The name used to describe an <em>individual</em> key.
       * @param valueType The {@link Class} of value this map contains.
       * @param valueTypeExtensions Does the <code>valueType</code> include
       * {@linkplain Property#getValueTypeExtensions() extensions}?
       * @param propName The name which uniquely identifies this property.
       * @param valueName The name used to describe an <em>individual</em> value.
       * @param values The child values being added.
       * @param keyToString A {@link Function} to create a {@link String} representation of the supplied keys.
       * @param valueToString A {@link Function} to create a {@link String} representation of the supplied
       * <code>values</code>.
       * @return The {@link Builder} this method was invoked on.
       */
      public <K,V> Builder<I> primitiveChild(final Class<K> keyType, final boolean keyTypeExtensions, final String keyName, final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Iterable<? extends Map.Entry<? extends K,? extends V>> values, final Function<K,String> keyToString, final Function<? super V,? extends String> valueToString) {
        return put(propName, new PrimitiveMap<K,V>(type, keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, valueName, (values != null) ? mapValues(values, (entry) -> new AbstractMap.SimpleImmutableEntry<@Nullable String,@Nullable String>((entry.getKey() != null) ? keyToString.apply(Objects.requireNonNull(entry.getKey())) : null, (entry.getValue() != null) ? valueToString.apply(Objects.requireNonNull(entry.getValue())) : null)) : null));
      }

      /**
       * Add a {@link Property} with a {@linkplain PrimitiveMap primitive map} of values.
       * 
       * @param <K> The type of key this map contains.
       * @param <V> The type of value this map contains.
       * @param keyType The {@link Class} of key this map contains.
       * @param keyTypeExtensions Does the <code>keyType</code> include {@linkplain MapChild#getKeyTypeExtensions()
       * extensions}?
       * @param keyName The name used to describe an <em>individual</em> key.
       * @param valueType The {@link Class} of value this map contains.
       * @param valueTypeExtensions Does the <code>valueType</code> include
       * {@linkplain Property#getValueTypeExtensions() extensions}?
       * @param propName The name which uniquely identifies this property.
       * @param valueName The name used to describe an <em>individual</em> value.
       * @param values The child values being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public <K,V> Builder<I> primitiveChild(final Class<K> keyType, final boolean keyTypeExtensions, final String keyName, final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Map<? extends K,? extends V> values) {
        return primitiveChild(keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, propName, valueName, (values != null) ? values.entrySet() : null, Object::toString, Object::toString);
      }

      /**
       * Add a {@link Property} with a {@linkplain ComplexMap complex map} of values.
       * 
       * @param <K> The type of key this map contains.
       * @param <V> The type of value this map contains.
       * @param keyType The {@link Class} of key this map contains.
       * @param keyTypeExtensions Does the <code>keyType</code> include {@linkplain MapChild#getKeyTypeExtensions()
       * extensions}?
       * @param keyName The name used to describe an <em>individual</em> key.
       * @param valueType The {@link Class} of value this map contains.
       * @param valueTypeExtensions Does the <code>valueType</code> include
       * {@linkplain Property#getValueTypeExtensions() extensions}?
       * @param propName The name which uniquely identifies this property.
       * @param valueName The name used to describe an <em>individual</em> value.
       * @param values The child values being added.
       * @param keyToString A {@link Function} to create a {@link String} representation of the supplied keys.
       * @return The {@link Builder} this method was invoked on.
       */
      public <K,V extends Introspectable> Builder<I> complexChild(final Class<K> keyType, final boolean keyTypeExtensions, final String keyName, final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Iterable<? extends Map.Entry<? extends K,? extends V>> values, final Function<? super K,? extends String> keyToString) {
        return put(propName, new ComplexMap<K,V>(type, keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, valueName, (values != null) ? mapValues(values, (entry) -> new AbstractMap.SimpleImmutableEntry<@Nullable String,@Nullable Info<V>>((entry.getKey() != null) ? keyToString.apply(Objects.requireNonNull(entry.getKey())) : null, (entry.getValue() != null) ? Introspectable.introspect(valueType, Objects.requireNonNull(entry.getValue())) : null)) : null));
      }

      /**
       * Add a {@link Property} with a {@linkplain ComplexMap complex map} of values.
       * 
       * @param <K> The type of key this map contains.
       * @param <V> The type of value this map contains.
       * @param keyType The {@link Class} of key this map contains.
       * @param keyTypeExtensions Does the <code>keyType</code> include {@linkplain MapChild#getKeyTypeExtensions()
       * extensions}?
       * @param keyName The name used to describe an <em>individual</em> key.
       * @param valueType The {@link Class} of value this map contains.
       * @param valueTypeExtensions Does the <code>valueType</code> include
       * {@linkplain Property#getValueTypeExtensions() extensions}?
       * @param propName The name which uniquely identifies this property.
       * @param valueName The name used to describe an <em>individual</em> value.
       * @param values The child values being added.
       * @return The {@link Builder} this method was invoked on.
       */
      public <K,V extends Introspectable> Builder<I> complexChild(final Class<K> keyType, final boolean keyTypeExtensions, final String keyName, final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Map<? extends K,? extends V> values) {
        return complexChild(keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, propName, valueName, (values != null) ? values.entrySet() : null, Object::toString);
      }

      /**
       * Compile the {@link Property} values from this {@link Builder} into an {@link Info} set.
       * 
       * @return An {@link Info} set containing all the {@link Property} values defined by this builder.
       */
      public Info<I> build() {
        return new Info<I>(type, namespace, lateBound, props);
      }

    } // Info.Builder

  } // Info

}
