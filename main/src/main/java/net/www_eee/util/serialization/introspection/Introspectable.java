/*
 * Copyright 2017-2017 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU AFFERO GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, a copy of which you should have received in the file LICENSE.txt.
 */

package net.www_eee.util.serialization.introspection;

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

import javax.xml.*;
import javax.xml.stream.*;

import org.eclipse.jdt.annotation.*;

import net.www_eee.util.serialization.xml.*;


/**
 * A lightweight interface for retrieving an object's property values, with built-in support for
 * {@linkplain XMLSerializable XML serialization}.
 */
@NonNullByDefault
public interface Introspectable extends XMLSerializable {

  public Info<?> introspect();

  @Override
  public default void writeXML(final XMLStreamWriter streamWriter, final @Nullable URI parentNamespace) throws XMLStreamException {
    introspect().writeXML(streamWriter, parentNamespace);
    return;
  }

  @SuppressWarnings("unchecked")
  public static <I extends Introspectable> Info<I> introspect(final Class<I> introspectableClass, final I introspectable) {
    final Info<?> info = introspectable.introspect();
    if (!introspectableClass.isAssignableFrom(info.getType())) throw new ClassCastException();
    return (Info<I>)info;
  }

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

    public Class<I> getType() {
      return type;
    }

    public @Nullable URI getNamespace() {
      return namespace;
    }

    @Override
    public Set<Map.Entry<String,Property<?,?>>> entrySet() {
      return props.entrySet();
    }

    @SuppressWarnings("unchecked")
    public <T extends Introspectable> Info<T> superCast(final Class<T> type) { // Too bad Java doesn't allow multiple type bounds on methods, or this would be: <T extends Introspectable & T super I>
      if (!type.isAssignableFrom(this.type)) throw new ClassCastException();
      return (Info<T>)this;
    }

    public Builder<I> build() {
      return new Builder<>(type, namespace, lateBound, props);
    }

    public static final <P extends Property<?,?>> Stream<Map.Entry<String,P>> filter(final Stream<Map.Entry<String,Property<?,?>>> props, final Class<P> propClass) {
      return props.filter((e) -> propClass.isInstance(e.getValue())).<Map.Entry<String,P>> map((e) -> new AbstractMap.SimpleImmutableEntry<>(e.getKey(), propClass.cast(e.getValue())));
    }

    public Map<String,Property<?,?>> getValues() {
      return entrySet().stream().filter((entry) -> !entry.getValue().isEmpty()).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (u, v) -> u, LinkedHashMap::new));
    }

    public Map<String,Attr<?>> getAttrs() {
      return filter(entrySet().stream(), Attr.WILDCARD_CLASS).filter((entry) -> entry.getValue().get().isPresent()).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (u, v) -> u, LinkedHashMap::new));
    }

    public Map<String,Child<?,?>> getChildren() {
      return filter(entrySet().stream(), Child.WILDCARD_CLASS).filter((entry) -> entry.getValue().get().hasNext()).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (u, v) -> u, LinkedHashMap::new));
    }

    @Override
    public int hashCode() {
      return props.hashCode();
    }

    @Override
    public boolean equals(final @Nullable Object other) {
      return Optional.ofNullable(other).filter(Info.class::isInstance).map(Info.class::cast).filter((i) -> type.equals(i.type)).filter((i) -> Objects.equals(namespace, i.namespace)).filter((i) -> lateBound == i.lateBound).filter((i) -> props.equals(i.props)).isPresent();
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

      for (final Map.Entry<String,Info.Attr<?>> attr : getAttrs().entrySet()) {
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
            propElementRequired = children.values().stream().filter((someChild) -> someChild != child.getValue()).filter((otherChild) -> !otherChild.getValueTypeExtensions()).filter((otherChild) -> valueNameFunction.apply(otherChild).equals(valueNameFunction.apply(child.getValue()))).findAny().isPresent(); // Will the value name for this prop collide with that of any other prop?
          }

          if (propElementRequired) streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, child.getKey(), nsString);

          if (Info.PrimitiveCollection.cast(child.getValue()).isPresent()) {

            final Info.PrimitiveCollection<?> primitiveCollection = Info.PrimitiveCollection.cast(child.getValue()).get();
            final Iterator<? extends @Nullable String> iter = primitiveCollection.get();
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
            final Iterator<? extends @Nullable Info<?>> iter = complexCollection.get();
            while (iter.hasNext()) {
              final @Nullable Info<?> value = iter.next();
              if (value != null) value.writeXML(streamWriter, getNamespace());
            }

          } else if (Info.PrimitiveMap.cast(child.getValue()).isPresent()) {

            final Info.PrimitiveMap<?,?> primitiveMap = Info.PrimitiveMap.cast(child.getValue()).get();
            final Iterator<? extends Map.Entry<? extends @Nullable String,? extends @Nullable String>> iter = primitiveMap.get();
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
            final Iterator<? extends Map.Entry<? extends @Nullable String,? extends @Nullable Info<?>>> iter = complexMap.get();
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

      public final Class<?> getDeclaringClass() {
        return declaringClass;
      }

      public final Class<V> getValueType() {
        return valueType;
      }

      public final boolean getValueTypeExtensions() {
        return valueTypeExtensions;
      }

      @Override
      public C get() {
        return value;
      }

      public abstract boolean isEmpty();

      abstract Property<V,C> forClass(Class<?> declaringClass);

      @Override
      public String toString() {
        return get().toString();
      }

    } // Info.Property

    public static final class Attr<V> extends Property<V,Optional<String>> {
      @SuppressWarnings("unchecked")
      static final Class<Attr<?>> WILDCARD_CLASS = (Class<Attr<?>>)(Object)Attr.class;

      private Attr(final Class<?> declaringClass, final Class<V> valueType, final boolean valueTypeExtensions, @Nullable String value) {
        super(declaringClass, valueType, valueTypeExtensions, Optional.ofNullable(value));
        return;
      }

      @Override
      Attr<V> forClass(Class<?> declaringClass) {
        return (this.declaringClass.equals(declaringClass)) ? this : new Attr<>(declaringClass, valueType, valueTypeExtensions, value.isPresent() ? value.get() : null);
      }

      @Override
      public boolean isEmpty() {
        return !get().isPresent();
      }

      @Override
      public String toString() {
        return String.valueOf(value.isPresent() ? value.get() : null);
      }

    } // Info.Attr

    public static abstract class Child<V,@NonNull C extends Iterator<?>> extends Property<V,C> {
      @SuppressWarnings("unchecked")
      static final Class<Child<?,?>> WILDCARD_CLASS = (Class<Child<?,?>>)(Object)Child.class;
      protected final String valueName;

      protected Child(final Class<?> declaringClass, final Class<V> valueType, final boolean valueTypeExtensions, final String valueName, final C values) {
        super(declaringClass, valueType, valueTypeExtensions, values);
        this.valueName = Objects.requireNonNull(valueName);
        return;
      }

      public String getValueName() {
        return valueName;
      }

      @Override
      public final boolean isEmpty() {
        return !get().hasNext();
      }

    } // Info.Child

    public static abstract class CollectionChild<V,@NonNull C extends Iterator<?>> extends Child<V,C> {
      @SuppressWarnings("unchecked")
      static final Class<CollectionChild<?,?>> WILDCARD_CLASS = (Class<CollectionChild<?,?>>)(Object)CollectionChild.class;

      private CollectionChild(final Class<?> declaringClass, final Class<V> valueType, final boolean valueTypeExtensions, final String valueName, final C values) {
        super(declaringClass, valueType, valueTypeExtensions, valueName, values);
        return;
      }

      public static final Optional<CollectionChild<?,?>> castCollection(final Property<?,?> prop) {
        return Optional.of(prop).filter(CollectionChild.class::isInstance).map(CollectionChild.class::cast);
      }

    } // Info.CollectionChild

    public static final class PrimitiveCollection<V> extends CollectionChild<V,Iterator<? extends @Nullable String>> {
      @SuppressWarnings("unchecked")
      static final Class<PrimitiveCollection<?>> WILDCARD_CLASS = (Class<PrimitiveCollection<?>>)(Object)PrimitiveCollection.class;

      private PrimitiveCollection(final Class<?> declaringClass, final Class<V> valueType, final boolean valueTypeExtensions, final String valueName, final @Nullable Iterator<? extends @Nullable String> values) {
        super(declaringClass, valueType, valueTypeExtensions, valueName, (values != null) ? values : Collections.emptyIterator());
        return;
      }

      public static final Optional<PrimitiveCollection<?>> cast(final Property<?,?> prop) {
        return Optional.of(prop).filter(PrimitiveCollection.class::isInstance).map(PrimitiveCollection.class::cast);
      }

      @Override
      PrimitiveCollection<V> forClass(Class<?> declaringClass) {
        return (this.declaringClass.equals(declaringClass)) ? this : new PrimitiveCollection<>(declaringClass, valueType, valueTypeExtensions, valueName, value);
      }

    } // Info.PrimitiveCollection

    public static final class ComplexCollection<V extends Introspectable> extends CollectionChild<V,Iterator<? extends @Nullable Info<V>>> {
      @SuppressWarnings("unchecked")
      static final Class<ComplexCollection<?>> WILDCARD_CLASS = (Class<ComplexCollection<?>>)(Object)ComplexCollection.class;

      private ComplexCollection(final Class<?> declaringClass, final Class<V> valueType, final boolean valueTypeExtensions, final String valueName, final @Nullable Iterator<? extends @Nullable Info<V>> values) {
        super(declaringClass, valueType, valueTypeExtensions, valueName, (values != null) ? values : Collections.emptyIterator());
        return;
      }

      public static final Optional<ComplexCollection<?>> cast(final Property<?,?> prop) {
        return Optional.of(prop).filter(ComplexCollection.class::isInstance).map(ComplexCollection.class::cast);
      }

      @Override
      ComplexCollection<V> forClass(Class<?> declaringClass) {
        return (this.declaringClass.equals(declaringClass)) ? this : new ComplexCollection<>(declaringClass, valueType, valueTypeExtensions, valueName, value);
      }

    } // Info.ComplexCollection

    public static abstract class MapChild<K,V,@NonNull C extends Iterator<? extends Map.Entry<? extends @Nullable String,?>>> extends Child<V,C> {
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

      public Class<K> getKeyType() {
        return keyType;
      }

      public boolean getKeyTypeExtensions() {
        return keyTypeExtensions;
      }

      public String getKeyName() {
        return keyName;
      }

      public static final Optional<MapChild<?,?,?>> castMap(final Property<?,?> prop) {
        return Optional.of(prop).filter(MapChild.class::isInstance).map(MapChild.class::cast);
      }

    } // Info.MapChild

    public static final class PrimitiveMap<K,V> extends MapChild<K,V,Iterator<? extends Map.Entry<? extends @Nullable String,? extends @Nullable String>>> {
      @SuppressWarnings("unchecked")
      static final Class<PrimitiveMap<?,?>> WILDCARD_CLASS = (Class<PrimitiveMap<?,?>>)(Object)PrimitiveMap.class;

      private PrimitiveMap(final Class<?> declaringClass, final Class<K> keyType, final boolean keyTypeExtensions, final String keyName, final Class<V> valueType, final boolean valueTypeExtensions, final String valueName, final @Nullable Iterator<? extends Map.Entry<? extends @Nullable String,? extends @Nullable String>> values) {
        super(declaringClass, keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, valueName, (values != null) ? values : Collections.emptyIterator());
        return;
      }

      public static final Optional<PrimitiveMap<?,?>> cast(final Property<?,?> prop) {
        return Optional.of(prop).filter(PrimitiveMap.class::isInstance).map(PrimitiveMap.class::cast);
      }

      @Override
      PrimitiveMap<K,V> forClass(Class<?> declaringClass) {
        return (this.declaringClass.equals(declaringClass)) ? this : new PrimitiveMap<>(declaringClass, keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, valueName, value);
      }

    } // Info.PrimitiveMap

    public static final class ComplexMap<K,V extends Introspectable> extends MapChild<K,V,Iterator<? extends Map.Entry<? extends @Nullable String,? extends @Nullable Info<V>>>> {
      @SuppressWarnings("unchecked")
      static final Class<ComplexMap<?,?>> WILDCARD_CLASS = (Class<ComplexMap<?,?>>)(Object)ComplexMap.class;

      private ComplexMap(final Class<?> declaringClass, final Class<K> keyType, final boolean keyTypeExtensions, final String keyName, final Class<V> valueType, final boolean valueTypeExtensions, final String valueName, final @Nullable Iterator<? extends Map.Entry<? extends @Nullable String,? extends @Nullable Info<V>>> values) {
        super(declaringClass, keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, valueName, (values != null) ? values : Collections.emptyIterator());
        return;
      }

      public static final Optional<ComplexMap<?,?>> cast(final Property<?,?> prop) {
        return Optional.of(prop).filter(ComplexMap.class::isInstance).map(ComplexMap.class::cast);
      }

      @Override
      ComplexMap<K,V> forClass(Class<?> declaringClass) {
        return (this.declaringClass.equals(declaringClass)) ? this : new ComplexMap<>(declaringClass, keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, valueName, value);
      }

    } // Info.ComplexMap

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

      public Builder(final Class<I> type, final @Nullable URI namespace, final boolean lateBound) {
        this(type, namespace, lateBound, null);
        return;
      }

      @SuppressWarnings("unchecked")
      public <T extends Introspectable> Builder<T> type(final Class<T> type) {
        return this.type.equals(type) ? (Builder<T>)this : new Builder<T>(type, namespace, lateBound, props);
      }

      private Builder<I> put(final String name, final Property<?,?> prop) {
        props.put(name, prop);
        return this;
      }

      public Builder<I> remove(final String propName) throws NoSuchElementException {
        Optional.ofNullable(props.remove(propName)).get();
        return this;
      }

      public Builder<I> rename(final String oldPropName, final String newPropName) throws NoSuchElementException {
        props.put(newPropName, Optional.ofNullable(props.remove(oldPropName)).get().forClass(type));
        return this;
      }

      public Builder<I> addAll(final Info<?> info) {
        props.putAll(info.props);
        return this;
      }

      private <T,R> Iterator<? extends R> mapValues(final Iterator<? extends T> iter, final Function<? super T,? extends R> mapper) {
        if (lateBound) {
          return new Iterator<R>() {

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
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iter, Spliterator.ORDERED), false).map(mapper).collect(Collectors.toList()).iterator();
      }

      public <V> Builder<I> attr(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final @Nullable V value, final Function<? super V,? extends String> valueToString) {
        return put(propName, new Attr<V>(type, valueType, valueTypeExtensions, (value != null) ? valueToString.apply(value) : null));
      }

      public <V> Builder<I> attr(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final Optional<? extends V> value, final Function<? super V,? extends String> valueToString) {
        return attr(valueType, valueTypeExtensions, propName, value.isPresent() ? value.get() : null, valueToString);
      }

      public <V> Builder<I> attr(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final @Nullable V value) {
        return attr(valueType, valueTypeExtensions, propName, value, Object::toString);
      }

      public <V> Builder<I> attr(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final Optional<? extends V> value) {
        return attr(valueType, valueTypeExtensions, propName, value.isPresent() ? value.get() : null);
      }

      public Builder<I> attr(final String propName, final @Nullable String value) {
        return attr(String.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final Optional<? extends String> value) {
        return attr(String.class, false, propName, value.isPresent() ? value.get() : null);
      }

      public Builder<I> attr(final String propName, final @Nullable Boolean value) {
        return attr(Boolean.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable Integer value) {
        return attr(Integer.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable Long value) {
        return attr(Long.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable Double value) {
        return attr(Double.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable Float value) {
        return attr(Float.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable Short value) {
        return attr(Short.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable UUID value) {
        return attr(UUID.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable URI value) {
        return attr(URI.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable URL value) {
        return attr(URL.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable Date value) {
        return attr(Date.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable Calendar value) {
        return attr(Calendar.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable Instant value) {
        return attr(Instant.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable Duration value) {
        return attr(Duration.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable LocalDate value) {
        return attr(LocalDate.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable LocalTime value) {
        return attr(LocalTime.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable LocalDateTime value) {
        return attr(LocalDateTime.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable ZonedDateTime value) {
        return attr(ZonedDateTime.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable OffsetDateTime value) {
        return attr(OffsetDateTime.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable OffsetTime value) {
        return attr(OffsetTime.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable Year value) {
        return attr(Year.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable YearMonth value) {
        return attr(YearMonth.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable Month value) {
        return attr(Month.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable MonthDay value) {
        return attr(MonthDay.class, false, propName, value);
      }

      public Builder<I> attr(final String propName, final @Nullable DayOfWeek value) {
        return attr(DayOfWeek.class, false, propName, value);
      }

      public <V> Builder<I> primitiveChild(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Iterator<? extends @Nullable V> values, final Function<? super V,? extends String> valueToString) {
        return put(propName, new PrimitiveCollection<V>(type, valueType, valueTypeExtensions, valueName, (values != null) ? mapValues(values, (value) -> (value != null) ? valueToString.apply(value) : null) : null));
      }

      public <V> Builder<I> primitiveChild(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Collection<? extends @Nullable V> values) {
        return primitiveChild(valueType, valueTypeExtensions, propName, valueName, (values != null) ? values.iterator() : null, Object::toString);
      }

      public Builder<I> primitiveChild(final String propName, final String valueName, final @Nullable Collection<? extends @Nullable String> values) {
        return primitiveChild(String.class, false, propName, valueName, values);
      }

      public <V extends Introspectable> Builder<I> complexChild(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Iterator<? extends @Nullable V> values) {
        return put(propName, new ComplexCollection<V>(type, valueType, valueTypeExtensions, valueName, (values != null) ? mapValues(values, (value) -> (value != null) ? Introspectable.introspect(valueType, value) : null) : null));
      }

      public <V extends Introspectable> Builder<I> complexChild(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Collection<? extends @Nullable V> values) {
        return complexChild(valueType, valueTypeExtensions, propName, valueName, (values != null) ? values.iterator() : null);
      }

      public <V extends Introspectable> Builder<I> complexChild(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final @Nullable V value) {
        return complexChild(valueType, valueTypeExtensions, propName, propName, (value != null) ? Collections.singleton(value).iterator() : null);
      }

      public <V extends Introspectable> Builder<I> complexChild(final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final Optional<V> value) {
        return complexChild(valueType, valueTypeExtensions, propName, value.isPresent() ? value.get() : null);
      }

      public <K,V> Builder<I> primitiveChild(final Class<K> keyType, final boolean keyTypeExtensions, final String keyName, final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Iterator<? extends Map.Entry<? extends K,? extends V>> values, final Function<K,String> keyToString, final Function<? super V,? extends String> valueToString) {
        return put(propName, new PrimitiveMap<K,V>(type, keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, valueName, (values != null) ? mapValues(values, (entry) -> new AbstractMap.SimpleImmutableEntry<@Nullable String,@Nullable String>((entry.getKey() != null) ? keyToString.apply(Objects.requireNonNull(entry.getKey())) : null, (entry.getValue() != null) ? valueToString.apply(Objects.requireNonNull(entry.getValue())) : null)) : null));
      }

      public <K,V> Builder<I> primitiveChild(final Class<K> keyType, final boolean keyTypeExtensions, final String keyName, final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Map<? extends K,? extends V> values) {
        return primitiveChild(keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, propName, valueName, (values != null) ? values.entrySet().iterator() : null, Object::toString, Object::toString);
      }

      public <K,V extends Introspectable> Builder<I> complexChild(final Class<K> keyType, final boolean keyTypeExtensions, final String keyName, final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Iterator<? extends Map.Entry<? extends K,? extends V>> values, final Function<? super K,? extends String> keyToString) {
        return put(propName, new ComplexMap<K,V>(type, keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, valueName, (values != null) ? mapValues(values, (entry) -> new AbstractMap.SimpleImmutableEntry<@Nullable String,@Nullable Info<V>>((entry.getKey() != null) ? keyToString.apply(Objects.requireNonNull(entry.getKey())) : null, (entry.getValue() != null) ? Introspectable.introspect(valueType, Objects.requireNonNull(entry.getValue())) : null)) : null));
      }

      public <K,V extends Introspectable> Builder<I> complexChild(final Class<K> keyType, final boolean keyTypeExtensions, final String keyName, final Class<V> valueType, final boolean valueTypeExtensions, final String propName, final String valueName, final @Nullable Map<? extends K,? extends V> values) {
        return complexChild(keyType, keyTypeExtensions, keyName, valueType, valueTypeExtensions, propName, valueName, (values != null) ? values.entrySet().iterator() : null, Object::toString);
      }

      public Info<I> build() {
        return new Info<I>(type, namespace, lateBound, props);
      }

    } // Info.Builder

  } // Info

}
