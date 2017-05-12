/*
 * Copyright 2016-2017 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU LESSER GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, a copy of which you should have received in the file LICENSE.txt.
 */

package net.www_eee.util.serialization.parser.xml;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import javax.xml.*;
import javax.xml.namespace.*;
import javax.xml.stream.*;
import javax.xml.stream.events.*;

import org.eclipse.jdt.annotation.*;

import org.jooq.*;
import org.jooq.exception.*;
import org.jooq.impl.*;


/**
 * This class allows you to {@linkplain #buildSchema(URI) define a schema} which can then be used to
 * {@linkplain #parse(InputStream) parse} XML, providing you with a {@link Stream} of dynamically constructed target
 * values.
 *
 * @param <T> The type of target values to be streamed.
 */
@NonNullByDefault
public class XMLStreamParser<@NonNull T> {
  private static final XMLInputFactory XML_INPUT_FACTORY = XMLInputFactory.newInstance();
  protected final Class<T> targetValueClass;
  private final ElementParser<?> documentParser;
  private final ElementParser<T> targetParser;

  protected XMLStreamParser(final Class<T> targetValueClass, final ElementParser<?> documentParser, final ElementParser<T> targetParser) {
    this.targetValueClass = Objects.requireNonNull(targetValueClass, "null targetValueClass");
    this.documentParser = Objects.requireNonNull(documentParser, "null documentParser");
    this.targetParser = Objects.requireNonNull(targetParser, "null targetParser");
    return;
  }

  /**
   * Get the type of target values streamed by this parser.
   * 
   * @return The type of target values streamed by this parser.
   */
  public Class<T> getTargetValueClass() {
    return targetValueClass;
  }

  /**
   * Parse the XML provided by the supplied {@link InputStream} into a {@link Stream} of target values.
   * 
   * @param inputStream The {@link InputStream} to read XML from.
   * @return A {@link Stream} of target values, as defined by your {@linkplain #buildSchema(URI) schema}.
   * @throws ParsingException If a problem was encountered while parsing.
   */
  public final Stream<T> parse(final InputStream inputStream) throws ParsingException {
    try {
      final XMLEventReader reader = XML_INPUT_FACTORY.createXMLEventReader(inputStream);

      ElementParser<?>.ParsingContextImpl targetParentContext = null;
      try {
        documentParser.parse(null, reader.nextTag(), reader, targetParser); // Read in events up until an element using the targetParser is encountered.
      } catch (TerminatingParserException tpe) {
        targetParentContext = tpe.getParsingContextImpl();
      }

      return (targetParentContext != null) ? StreamSupport.stream(new TargetValueSpliterator(targetParentContext, reader), false) : Stream.empty();
    } catch (XMLStreamException xse) {
      throw new XMLStreamParsingException(xse);
    }
  }

  /**
   * Read in and discard all subsequent events for the given start element, up to and including it's end element.
   * 
   * @param element The element to skip over.
   * @param reader The source of events.
   * @throws XMLStreamParsingException If there was a problem skipping this element.
   */
  private static final void skip(final StartElement element, final XMLEventReader reader) throws XMLStreamParsingException {
    try {
      int depth = 1;
      XMLEvent e = reader.nextEvent();
      while (depth > 0) {
        if (e.isStartElement()) {
          depth++;
        } else if (e.isEndElement()) {
          depth--;
        }
        if (depth > 0) e = reader.nextEvent();
      }
    } catch (XMLStreamException xse) {
      throw new XMLStreamParsingException(xse);
    }
    return;
  }

  private static final void ignoreEvent(final XMLEvent event, final XMLEventReader reader) throws XMLStreamParsingException {
    if (event.isStartElement()) { // OK, swallow all the content for this...
      skip(event.asStartElement(), reader);
    } // Other element types don't have children, so we don't have to do anything else to ignore them in their entirety.
    return;
  }

  /**
   * Create a {@link SchemaBuilder SchemaBuilder} which can then be used to define the elements used within the XML
   * documents you wish to {@link SchemaBuilder#createParser(Class, QName, QName) create a parser} for.
   * 
   * @param namespace The (optional) namespace used by your XML.
   * @return A new {@link SchemaBuilder}.
   */
  @SuppressWarnings("unchecked")
  public static SchemaBuilder<@NonNull ? extends SchemaBuilder<@NonNull ?>> buildSchema(final @Nullable URI namespace) {
    return new SchemaBuilder<>((Class<SchemaBuilder<?>>)(Object)SchemaBuilder.class, namespace, null, false);
  }

  @SuppressWarnings("unchecked")
  protected static final <@NonNull T> ElementParser<T>.ParsingContextImpl cast(final ElementParsingContext<T> ctx) {
    return (ElementParser<T>.ParsingContextImpl)(Object)ctx;
  }

  /**
   * <p>
   * An interface providing information about an {@linkplain #getStartElement() element} currently being parsed,
   * including it's {@linkplain #getElementName() name}, {@linkplain #getTargetValueClass() target value type},
   * {@linkplain #getElementContext() context}, {@linkplain #getAttrs() attributes}, {@linkplain #getChildValues()
   * children}, etc. Generally, when the parser needs to calculate the {@linkplain #getTargetValueClass() target value}
   * for a newly parsed element, an implementation of this interface will be provided to a target value calculation
   * {@link Function}, which is generally supplied by the user when an element is
   * {@linkplain XMLStreamParser.SchemaBuilder#defineElement(String, Class, Function) defined}.
   * </p>
   * 
   * <p>
   * In addition to information about the element currently being parsed, this interface also provides access to a
   * global store of {@linkplain #getSavedValues(QName, Class) saved element values}, and the ability to construct
   * values by {@linkplain #getInjectedValue(Class, Map) injecting} it's information into them.
   * </p>
   * 
   * @param <T> The {@linkplain #getTargetValueClass() target value class} of the element being parsed.
   */
  public interface ElementParsingContext<@NonNull T> {

    /**
     * Get the {@link Class} of target value produced by the element currently being parsed.
     * 
     * @return The {@link Class} of target value produced by the element currently being parsed.
     */
    public Class<T> getTargetValueClass();

    /**
     * Get the {@linkplain QName qualified name} of the element currently being parsed.
     * 
     * @return The {@linkplain QName qualified name} of the element currently being parsed.
     */
    public QName getElementName();

    /**
     * Get the {@linkplain QName#getNamespaceURI() namespace URI} of the element currently being parsed.
     * 
     * @return The {@linkplain QName#getNamespaceURI() namespace URI} of the element currently being parsed.
     */
    public default String getNamespaceURI() {
      return getElementName().getNamespaceURI();
    }

    /**
     * Get the {@link StartElement} currently being parsed.
     * 
     * @return The {@link StartElement} currently being parsed.
     */
    public StartElement getStartElement();

    /**
     * Get the stack of {@link StartElement}'s from the document root element down to the element currently being
     * parsed.
     * 
     * @return A {@link Deque} containing the stack of {@link StartElement}'s from the document root element down to the
     * element currently being parsed.
     */
    public Deque<StartElement> getElementContext();

    /**
     * Get the {@link StartElement#getAttributes() attributes} from the element currently being parsed.
     * 
     * @return A {@link Map} containing the {@link StartElement#getAttributes() attributes} on the element currently
     * being parsed.
     */
    @SuppressWarnings("unchecked")
    public default Map<QName,String> getAttrs() {
      return Collections.unmodifiableMap(StreamSupport.stream(Spliterators.spliteratorUnknownSize((Iterator<Attribute>)getStartElement().getAttributes(), Spliterator.NONNULL | Spliterator.DISTINCT | Spliterator.IMMUTABLE), false).map((attr) -> new AbstractMap.SimpleImmutableEntry<>(attr.getName(), attr.getValue())).collect(Collectors.<Map.Entry<QName,String>,QName,String> toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /**
     * Get the {@linkplain Attribute#getValue() value} of an {@linkplain StartElement#getAttributeByName(QName)
     * attribute} from the element currently being parsed.
     * 
     * @param attrName The name of the attribute value to retrieve.
     * @return The value of the attribute, or <code>null</code> if there is no such attribute.
     */
    public default @Nullable String getAttrOrNull(final QName attrName) {
      final @Nullable Attribute attr = getStartElement().getAttributeByName(attrName);
      return (attr != null) ? attr.getValue() : null;
    }

    /**
     * Get the {@linkplain Attribute#getValue() value} of an {@linkplain StartElement#getAttributeByName(QName)
     * attribute} from the element currently being parsed.
     * 
     * @param <AT> The type of value produced by the supplied <code>attrValueFunction</code>.
     * @param attrName The name of the attribute value to retrieve.
     * @param attrValueFunction A {@link Function} which should be applied to the attribute value before it's returned.
     * @return The value of the attribute, or <code>null</code> if there is no such attribute.
     */
    public default <AT> @Nullable AT getAttrOrNull(final QName attrName, final Function<? super String,? extends AT> attrValueFunction) {
      final @Nullable String attr = getAttrOrNull(attrName);
      return (attr != null) ? attrValueFunction.apply(attr) : null;
    }

    /**
     * Get the {@linkplain Attribute#getValue() value} of an {@linkplain StartElement#getAttributeByName(QName)
     * attribute} from the element currently being parsed.
     * 
     * @param attrLocalName The {@linkplain QName#getLocalPart() local name} of the attribute value to retrieve
     * ({@linkplain XMLConstants#NULL_NS_URI no namespace} will be used).
     * @return The value of the attribute, or <code>null</code> if there is no such attribute.
     */
    public default @Nullable String getAttrOrNull(final String attrLocalName) {
      return getAttrOrNull(new QName(XMLConstants.NULL_NS_URI, attrLocalName));
    }

    /**
     * Get the {@linkplain Attribute#getValue() value} of an {@linkplain StartElement#getAttributeByName(QName)
     * attribute} from the element currently being parsed.
     * 
     * @param <AT> The type of value produced by the supplied <code>attrValueFunction</code>.
     * @param attrLocalName The {@linkplain QName#getLocalPart() local name} of the attribute value to retrieve
     * ({@linkplain XMLConstants#NULL_NS_URI no namespace} will be used).
     * @param attrValueFunction A {@link Function} which should be applied to the attribute value before it's returned.
     * @return The value of the attribute, or <code>null</code> if there is no such attribute.
     */
    public default <AT> @Nullable AT getAttrOrNull(final String attrLocalName, final Function<? super String,? extends AT> attrValueFunction) {
      final @Nullable String attr = getAttrOrNull(attrLocalName);
      return (attr != null) ? attrValueFunction.apply(attr) : null;
    }

    /**
     * Get the {@linkplain Attribute#getValue() value} of an {@linkplain StartElement#getAttributeByName(QName)
     * attribute} from the element currently being parsed.
     * 
     * @param attrName The name of the attribute value to retrieve.
     * @return An {@link Optional} containing the value of the attribute, which will be {@linkplain Optional#empty()
     * empty} if there is no such attribute.
     */
    public default Optional<String> getOptionalAttr(final QName attrName) {
      return Optional.ofNullable(getStartElement().getAttributeByName(attrName)).map(Attribute::getValue);
    }

    /**
     * Get the {@linkplain Attribute#getValue() value} of an {@linkplain StartElement#getAttributeByName(QName)
     * attribute} from the element currently being parsed.
     * 
     * @param <AT> The type of value produced by the supplied <code>attrValueFunction</code>.
     * @param attrName The name of the attribute value to retrieve.
     * @param attrValueFunction A {@link Function} which should be applied to the attribute value before it's returned.
     * @return An {@link Optional} containing the value of the attribute, which will be {@linkplain Optional#empty()
     * empty} if there is no such attribute.
     */
    public default <AT> Optional<AT> getOptionalAttr(final QName attrName, final Function<? super String,? extends AT> attrValueFunction) {
      return getOptionalAttr(attrName).map(attrValueFunction);
    }

    /**
     * Get the {@linkplain Attribute#getValue() value} of an {@linkplain StartElement#getAttributeByName(QName)
     * attribute} from the element currently being parsed.
     * 
     * @param attrLocalName The {@linkplain QName#getLocalPart() local name} of the attribute value to retrieve
     * ({@linkplain XMLConstants#NULL_NS_URI no namespace} will be used).
     * @return An {@link Optional} containing the value of the attribute, which will be {@linkplain Optional#empty()
     * empty} if there is no such attribute.
     */
    public default Optional<String> getOptionalAttr(final String attrLocalName) {
      return getOptionalAttr(new QName(XMLConstants.NULL_NS_URI, attrLocalName));
    }

    /**
     * Get the {@linkplain Attribute#getValue() value} of an {@linkplain StartElement#getAttributeByName(QName)
     * attribute} from the element currently being parsed.
     * 
     * @param <AT> The type of value produced by the supplied <code>attrValueFunction</code>.
     * @param attrLocalName The {@linkplain QName#getLocalPart() local name} of the attribute value to retrieve
     * ({@linkplain XMLConstants#NULL_NS_URI no namespace} will be used).
     * @param attrValueFunction A {@link Function} which should be applied to the attribute value before it's returned.
     * @return An {@link Optional} containing the value of the attribute, which will be {@linkplain Optional#empty()
     * empty} if there is no such attribute.
     */
    public default <AT> Optional<AT> getOptionalAttr(final String attrLocalName, final Function<? super String,? extends AT> attrValueFunction) {
      return getOptionalAttr(attrLocalName).map(attrValueFunction);
    }

    /**
     * Get the {@linkplain Attribute#getValue() value} of an {@linkplain StartElement#getAttributeByName(QName)
     * attribute} from the element currently being parsed.
     * 
     * @param attrName The name of the attribute value to retrieve.
     * @return The value of the attribute (never <code>null</code>).
     * @throws NoSuchElementException If there is no such attribute.
     */
    public default String getRequiredAttr(final QName attrName) throws NoSuchElementException {
      return getOptionalAttr(attrName).orElseThrow(() -> new NoSuchElementException("Element '" + getElementName().getLocalPart() + "' has no '" + attrName.getLocalPart() + "' attribute"));
    }

    /**
     * Get the {@linkplain Attribute#getValue() value} of an {@linkplain StartElement#getAttributeByName(QName)
     * attribute} from the element currently being parsed.
     * 
     * @param <AT> The type of value produced by the supplied <code>attrValueFunction</code>.
     * @param attrName The name of the attribute value to retrieve.
     * @param attrValueFunction A {@link Function} which should be applied to the attribute value before it's returned.
     * @return The value of the attribute (never <code>null</code>).
     * @throws NoSuchElementException If there is no such attribute.
     */
    public default <AT> AT getRequiredAttr(final QName attrName, final Function<? super String,? extends AT> attrValueFunction) throws NoSuchElementException {
      return attrValueFunction.apply(getRequiredAttr(attrName));
    }

    /**
     * Get the {@linkplain Attribute#getValue() value} of an {@linkplain StartElement#getAttributeByName(QName)
     * attribute} from the element currently being parsed.
     * 
     * @param attrLocalName The {@linkplain QName#getLocalPart() local name} of the attribute value to retrieve
     * ({@linkplain XMLConstants#NULL_NS_URI no namespace} will be used).
     * @return The value of the attribute (never <code>null</code>).
     * @throws NoSuchElementException If there is no such attribute.
     */
    public default String getRequiredAttr(final String attrLocalName) throws NoSuchElementException {
      return getRequiredAttr(new QName(XMLConstants.NULL_NS_URI, attrLocalName));
    }

    /**
     * Get the {@linkplain Attribute#getValue() value} of an {@linkplain StartElement#getAttributeByName(QName)
     * attribute} from the element currently being parsed.
     * 
     * @param <AT> The type of value produced by the supplied <code>attrValueFunction</code>.
     * @param attrLocalName The {@linkplain QName#getLocalPart() local name} of the attribute value to retrieve
     * ({@linkplain XMLConstants#NULL_NS_URI no namespace} will be used).
     * @param attrValueFunction A {@link Function} which should be applied to the attribute value before it's returned.
     * @return The value of the attribute (never <code>null</code>).
     * @throws NoSuchElementException If there is no such attribute.
     */
    public default <AT> AT getRequiredAttr(final String attrLocalName, final Function<? super String,? extends AT> attrValueFunction) throws NoSuchElementException {
      return attrValueFunction.apply(getRequiredAttr(attrLocalName));
    }

    /**
     * Get target values saved by a previously parsed element. The element must have been defined as having it's target
     * values saved.
     * 
     * @param <ST> The target value type of the saved values.
     * @param savedElementName The name of the element for which saved values are desired. If <code>null</code>, the
     * returned values can come from any element having the specified <code>targetValueClass</code>.
     * @param savedElementTargetValueClass The target value class of the desired saved values.
     * @return A {@link Stream} of values (never <code>null</code>).
     */
    public <@NonNull ST> Stream<ST> getSavedValues(final @Nullable QName savedElementName, final Class<ST> savedElementTargetValueClass);

    /**
     * Get target values saved by a previously parsed element. The element must have been defined as having it's target
     * values saved.
     * 
     * @param <ST> The target value type of the saved value.
     * @param savedElementLocalName The {@linkplain QName#getLocalPart() local name} of the element for which saved
     * values are desired (the {@linkplain #getNamespaceURI() namespace for this element} will be used). If
     * <code>null</code>, the returned values can come from any element having the specified
     * <code>targetValueClass</code>.
     * @param savedElementTargetValueClass The target value class of the desired saved values.
     * @return A {@link Stream} of values (never <code>null</code>).
     */
    public default <@NonNull ST> Stream<ST> getSavedValues(final @Nullable String savedElementLocalName, final Class<ST> savedElementTargetValueClass) {
      return getSavedValues((savedElementLocalName != null) ? new QName(getNamespaceURI(), savedElementLocalName) : null, savedElementTargetValueClass);
    }

    /**
     * Get the first target value saved by a previously parsed element. The element must have been defined as having
     * it's target values saved.
     * 
     * @param <ST> The target value type of the saved value.
     * @param savedElementName The name of the element for which the saved value is desired. If <code>null</code>, the
     * returned value can come from any element having the specified <code>targetValueClass</code>.
     * @param savedElementTargetValueClass The target value class of the desired saved values.
     * @return An {@link Optional} containing the first saved value, which will be {@linkplain Optional#empty() empty}
     * if there are none.
     */
    public default <@NonNull ST> Optional<ST> getOptionalSavedValue(final @Nullable QName savedElementName, final Class<ST> savedElementTargetValueClass) {
      return getSavedValues(savedElementName, savedElementTargetValueClass).findFirst();
    }

    /**
     * Get the first target value saved by a previously parsed element. The element must have been defined as having
     * it's target values saved.
     * 
     * @param <ST> The target value type of the saved value.
     * @param savedElementLocalName The {@linkplain QName#getLocalPart() local name} of the element for which saved
     * values are desired (the {@linkplain #getNamespaceURI() namespace for this element} will be used). If
     * <code>null</code>, the returned value can come from any element having the specified
     * <code>targetValueClass</code>.
     * @param savedElementTargetValueClass The target value class of the desired saved value.
     * @return An {@link Optional} containing the first saved value, which will be {@linkplain Optional#empty() empty}
     * if there are none.
     */
    public default <@NonNull ST> Optional<ST> getOptionalSavedValue(final @Nullable String savedElementLocalName, final Class<ST> savedElementTargetValueClass) {
      return getOptionalSavedValue((savedElementLocalName != null) ? new QName(getNamespaceURI(), savedElementLocalName) : null, savedElementTargetValueClass);
    }

    /**
     * Get the first target value saved by a previously parsed element. The element must have been defined as having
     * it's target values saved.
     * 
     * @param <ST> The target value type of the saved value.
     * @param savedElementName The name of the element for which the saved value is desired. If <code>null</code>, the
     * returned value can come from any element having the specified <code>targetValueClass</code>.
     * @param savedElementTargetValueClass The target value class of the desired saved values.
     * @return The first saved value, or <code>null</code> if there are none.
     */
    public default <@NonNull ST> @Nullable ST getSavedValueOrNull(final @Nullable QName savedElementName, final Class<ST> savedElementTargetValueClass) {
      final Optional<ST> saved = getOptionalSavedValue(savedElementName, savedElementTargetValueClass);
      return saved.isPresent() ? saved.get() : null;
    }

    /**
     * Get the first target value saved by a previously parsed element. The element must have been defined as having
     * it's target values saved.
     * 
     * @param <ST> The target value type of the saved value.
     * @param savedElementLocalName The {@linkplain QName#getLocalPart() local name} of the element for which saved
     * values are desired (the {@linkplain #getNamespaceURI() namespace for this element} will be used). If
     * <code>null</code>, the returned value can come from any element having the specified
     * <code>targetValueClass</code>.
     * @param savedElementTargetValueClass The target value class of the desired saved value.
     * @return The first saved value, or <code>null</code> if there are none.
     */
    public default <@NonNull ST> @Nullable ST getSavedValueOrNull(final @Nullable String savedElementLocalName, final Class<ST> savedElementTargetValueClass) {
      final Optional<ST> saved = getOptionalSavedValue(savedElementLocalName, savedElementTargetValueClass);
      return saved.isPresent() ? saved.get() : null;
    }

    /**
     * Get the first target value saved by a previously parsed element. The element must have been defined as having
     * it's target values saved.
     * 
     * @param <ST> The target value type of the saved value.
     * @param savedElementName The name of the element for which the saved value is desired. If <code>null</code>, the
     * returned value can come from any element having the specified <code>targetValueClass</code>.
     * @param savedElementTargetValueClass The target value class of the desired saved values.
     * @return The first saved value (never <code>null</code>).
     * @throws NoSuchElementException If there is no matching saved value.
     */
    public default <@NonNull ST> ST getRequiredSavedValue(final @Nullable QName savedElementName, final Class<ST> savedElementTargetValueClass) throws NoSuchElementException {
      return getOptionalSavedValue(savedElementName, savedElementTargetValueClass).orElseThrow(() -> new NoSuchElementException("No " + ((savedElementName != null) ? "'" + savedElementName.getLocalPart() + "' " : "") + " saved element with '" + savedElementTargetValueClass.getSimpleName() + "' target value class found"));
    }

    /**
     * Get the first target value saved by a previously parsed element. The element must have been defined as having
     * it's target values saved.
     * 
     * @param <ST> The target value type of the saved value.
     * @param savedElementLocalName The {@linkplain QName#getLocalPart() local name} of the element for which saved
     * values are desired (the {@linkplain #getNamespaceURI() namespace for this element} will be used). If
     * <code>null</code>, the returned value can come from any element having the specified
     * <code>targetValueClass</code>.
     * @param savedElementTargetValueClass The target value class of the desired saved value.
     * @return The first saved value (never <code>null</code>).
     * @throws NoSuchElementException If there is no matching saved value.
     */
    public default <@NonNull ST> ST getRequiredSavedValue(final @Nullable String savedElementLocalName, final Class<ST> savedElementTargetValueClass) throws NoSuchElementException {
      return getRequiredSavedValue((savedElementLocalName != null) ? new QName(getNamespaceURI(), savedElementLocalName) : null, savedElementTargetValueClass);
    }

    /**
     * Get target values from children of the element currently being parsed.
     * 
     * @return A {@link Stream} of child element values of the form
     * <code>{@link java.util.Map.Entry Map.Entry}&lt;{@link java.util.Map.Entry Map.Entry}&lt;ChildElementName,TargetValueClass&gt;,{@link List}&lt;TargetValue&gt;&gt;</code>.
     */
    public Stream<Map.Entry<Map.Entry<QName,Class<?>>,List<?>>> getChildValues();

    /**
     * Get target values from a child of the element currently being parsed.
     * 
     * @param <CT> The target value type of the child values.
     * @param childElementName The name of the element for which child target values are desired. If <code>null</code>,
     * the returned values can come from any element having the specified <code>targetValueClass</code>.
     * @param childElementTargetValueClass The target value class of the desired child values.
     * @return A {@link Stream} of values (never <code>null</code>).
     */
    public default <@NonNull CT> Stream<CT> getChildValues(final @Nullable QName childElementName, final Class<CT> childElementTargetValueClass) {
      return getChildValues().filter((entry) -> (childElementName == null) || (childElementName.equals(entry.getKey().getKey()))).filter((entry) -> childElementTargetValueClass.isAssignableFrom(entry.getKey().getValue())).<List<?>> map(Map.Entry::getValue).flatMap(List::stream).map((value) -> childElementTargetValueClass.cast(value));
    }

    /**
     * Get target values from a child of the element currently being parsed.
     * 
     * @param <CT> The target value type of the child values.
     * @param childElementLocalName The {@linkplain QName#getLocalPart() local name} of the element for which child
     * target values are desired (the {@linkplain #getNamespaceURI() namespace for this element} will be used). If
     * <code>null</code>, the returned values can come from any element having the specified
     * <code>childElementTargetValueClass</code>.
     * @param childElementTargetValueClass The target value class of the desired child values.
     * @return A {@link Stream} of values (never <code>null</code>).
     */
    public default <@NonNull CT> Stream<CT> getChildValues(final @Nullable String childElementLocalName, final Class<CT> childElementTargetValueClass) {
      return getChildValues((childElementLocalName != null) ? new QName(getNamespaceURI(), childElementLocalName) : null, childElementTargetValueClass);
    }

    /**
     * Get the first target value from a child of the element currently being parsed.
     * 
     * @param <CT> The target value type of the child value.
     * @param childElementName The name of the element for which the child target value is desired. If
     * <code>null</code>, the returned value can come from any element having the specified
     * <code>childElementTargetValueClass</code>.
     * @param childElementTargetValueClass The target value class of the desired child value.
     * @return An {@link Optional} containing the first child value, which will be {@linkplain Optional#empty() empty}
     * if there are none.
     */
    public default <@NonNull CT> Optional<CT> getOptionalChildValue(final @Nullable QName childElementName, final Class<CT> childElementTargetValueClass) {
      return getChildValues(childElementName, childElementTargetValueClass).findFirst();
    }

    /**
     * Get the first target value from a child of the element currently being parsed.
     * 
     * @param <CT> The target value type of the child value.
     * @param childElementLocalName The {@linkplain QName#getLocalPart() local name} of the element for which the child
     * target value is desired (the {@linkplain #getNamespaceURI() namespace for this element} will be used). If
     * <code>null</code>, the returned value can come from any element having the specified
     * <code>childElementTargetValueClass</code>.
     * @param childElementTargetValueClass The target value class of the desired child value.
     * @return An {@link Optional} containing the first child value, which will be {@linkplain Optional#empty() empty}
     * if there are none.
     */
    public default <@NonNull CT> Optional<CT> getOptionalChildValue(final @Nullable String childElementLocalName, final Class<CT> childElementTargetValueClass) {
      return getOptionalChildValue((childElementLocalName != null) ? new QName(getNamespaceURI(), childElementLocalName) : null, childElementTargetValueClass);
    }

    /**
     * Get the first target value from a child of the element currently being parsed.
     * 
     * @param <CT> The target value type of the child value.
     * @param childElementName The name of the element for which the child target value is desired. If
     * <code>null</code>, the returned value can come from any element having the specified
     * <code>childElementTargetValueClass</code>.
     * @param childElementTargetValueClass The target value class of the desired child value.
     * @return The first child value, or <code>null</code> if there are none.
     */
    public default <@NonNull CT> @Nullable CT getChildValueOrNull(final @Nullable QName childElementName, final Class<CT> childElementTargetValueClass) {
      final Optional<CT> child = getOptionalChildValue(childElementName, childElementTargetValueClass);
      return child.isPresent() ? child.get() : null;
    }

    /**
     * Get the first target value from a child of the element currently being parsed.
     * 
     * @param <CT> The target value type of the child value.
     * @param childElementLocalName The {@linkplain QName#getLocalPart() local name} of the element for which the child
     * target value is desired (the {@linkplain #getNamespaceURI() namespace for this element} will be used). If
     * <code>null</code>, the returned value can come from any element having the specified
     * <code>childElementTargetValueClass</code>.
     * @param childElementTargetValueClass The target value class of the desired child value.
     * @return The first child value, or <code>null</code> if there are none.
     */
    public default <@NonNull CT> @Nullable CT getChildValueOrNull(final @Nullable String childElementLocalName, final Class<CT> childElementTargetValueClass) {
      final Optional<CT> child = getOptionalChildValue(childElementLocalName, childElementTargetValueClass);
      return child.isPresent() ? child.get() : null;
    }

    /**
     * Get the first target value from a child of the element currently being parsed.
     * 
     * @param <CT> The target value type of the child value.
     * @param childElementName The name of the element for which the child target value is desired. If
     * <code>null</code>, the returned value can come from any element having the specified
     * <code>childElementTargetValueClass</code>.
     * @param childElementTargetValueClass The target value class of the desired child value.
     * @return The first child value (never <code>null</code>).
     * @throws NoSuchElementException If there is no matching child value.
     */
    public default <@NonNull CT> CT getRequiredChildValue(final @Nullable QName childElementName, final Class<CT> childElementTargetValueClass) throws NoSuchElementException {
      return getOptionalChildValue(childElementName, childElementTargetValueClass).orElseThrow(() -> new NoSuchElementException("No " + ((childElementName != null) ? "'" + childElementName.getLocalPart() + "' " : "") + " child element with '" + childElementTargetValueClass.getSimpleName() + "' target class found"));
    }

    /**
     * Get the first target value from a child of the element currently being parsed.
     * 
     * @param <CT> The target value type of the child value.
     * @param childElementLocalName The {@linkplain QName#getLocalPart() local name} of the element for which the child
     * target value is desired (the {@linkplain #getNamespaceURI() namespace for this element} will be used). If
     * <code>null</code>, the returned value can come from any element having the specified
     * <code>childElementTargetValueClass</code>.
     * @param childElementTargetValueClass The target value class of the desired child value.
     * @return The first child value (never <code>null</code>).
     * @throws NoSuchElementException If there is no matching child value.
     */
    public default <@NonNull CT> CT getRequiredChildValue(final @Nullable String childElementLocalName, final Class<CT> childElementTargetValueClass) throws NoSuchElementException {
      return getRequiredChildValue((childElementLocalName != null) ? new QName(getNamespaceURI(), childElementLocalName) : null, childElementTargetValueClass);
    }

    /**
     * <p>
     * Construct a new instance of the specified <code>injectedValueClass</code> by creating a {@link Record} containing
     * the data from the element currently being parsed and {@linkplain Record#into(Class) injecting} it into the
     * target.
     * </p>
     * 
     * <p>
     * The {@link Record} created to perform the injection will automatically be populated with a {@link Field} for each
     * of the {@linkplain #getAttrs() attributes} and {@linkplain #getChildValues() child values} from the element
     * currently being parsed (using the {@linkplain QName#getLocalPart() local name} as the injected
     * {@linkplain Field#getName() field name} for each). Child values will be injected as an
     * {@link java.lang.reflect.Array Array} in order to provide strong typing information for use by the
     * {@link DefaultRecordMapper}. You can provide <code>injectionSpecs</code> to override this default behaviour.
     * </p>
     * 
     * @param <IT> The type of object being injected.
     * @param injectedValueClass The {@link Class} of object being injected.
     * @param injectionSpecs This is an optional set of mappings from an injected field name to a {@link Function} for
     * retrieving a value to be injected into that field based on the current
     * {@link XMLStreamParser.ElementParsingContext ElementParsingContext}. These mappings can be used to either alter
     * the default values being injected or to supplement them with additional ones.
     * @return The injected target value.
     * @throws ElementValueParsingException If a {@link MappingException} exception occurred while injecting the object.
     * @see DefaultRecordMapper
     * @see #getInjectedValue(Class)
     */
    public default <@NonNull IT> IT getInjectedValue(final Class<IT> injectedValueClass, final @Nullable Map<String,Function<ElementParsingContext<T>,@Nullable Object>> injectionSpecs) throws ElementValueParsingException {
      final Map<String,Field<Object>> fields = new HashMap<>();
      final Consumer<String> defineField = (fieldName) -> fields.put(fieldName, DSL.field(DSL.name(fieldName)));
      getAttrs().keySet().stream().map(QName::getLocalPart).forEach(defineField);
      getChildValues().map(Map.Entry::getKey).map(Map.Entry::getKey).map(QName::getLocalPart).forEach(defineField);
      if (injectionSpecs != null) injectionSpecs.keySet().forEach(defineField);

      final Record record = DSL.using(SQLDialect.DEFAULT).newRecord(fields.values().stream().toArray(Field<?>[]::new));

      getAttrs().entrySet().forEach((entry) -> record.<Object> set(Objects.requireNonNull(fields.get(entry.getKey().getLocalPart())), entry.getValue()));
      getChildValues().forEach((entry) -> new AbstractMap.SimpleImmutableEntry<String,Object>(entry.getKey().getKey().getLocalPart(), entry.getValue().toArray((Object[])java.lang.reflect.Array.newInstance(entry.getKey().getValue(), entry.getValue().size()))));
      if (injectionSpecs != null) injectionSpecs.entrySet().forEach((entry) -> record.<Object> set(Objects.requireNonNull(fields.get(entry.getKey())), entry.getValue().apply(this)));

      try {
        return record.into(injectedValueClass);
      } catch (MappingException me) {
        throw new ElementValueParsingException(me, cast(this));
      }
    }

    /**
     * <p>
     * Construct a new instance of the specified <code>injectedValueClass</code> by creating a {@link Record} containing
     * the data from the element currently being parsed and {@linkplain Record#into(Class) injecting} it into the
     * target.
     * </p>
     * 
     * <p>
     * The {@link Record} created to perform the injection will automatically be populated with a {@link Field} for each
     * of the {@linkplain #getAttrs() attributes} and {@linkplain #getChildValues() child values} from the element
     * currently being parsed (using the {@linkplain QName#getLocalPart() local name} as the injected
     * {@linkplain Field#getName() field name} for each). Child values will be injected as an
     * {@link java.lang.reflect.Array Array} in order to provide strong typing information for use by the
     * {@link DefaultRecordMapper}. You can provide <code>injectionSpecs</code> to override this default behaviour.
     * </p>
     * 
     * @param <IT> The type of object being injected.
     * @param injectedValueClass The {@link Class} of object being injected.
     * @return The injected target value.
     * @throws ElementValueParsingException If a {@link MappingException} exception occurred while injecting the object.
     * @see DefaultRecordMapper
     * @see #getInjectedValue(Class, Map)
     */
    public default <@NonNull IT> IT getInjectedValue(final Class<IT> injectedValueClass) throws ElementValueParsingException {
      return getInjectedValue(injectedValueClass, null);
    }

  } // ElementParsingContext

  private final class TargetValueSpliterator implements Spliterator<T> {
    private final int CHARACTERISTICS = 0 | Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.NONNULL | Spliterator.IMMUTABLE;
    private final ElementParser<?>.ParsingContextImpl parentContext;
    private final XMLEventReader reader;

    public TargetValueSpliterator(final ElementParser<?>.ParsingContextImpl parentContext, final XMLEventReader reader) throws IllegalArgumentException {
      if (!parentContext.getParser().getChildParsers().stream().anyMatch(targetParser::equals)) throw new IllegalStateException("Current parser not parent of target parser");
      this.parentContext = parentContext;
      this.reader = reader;
      return;
    }

    @Override
    public int characteristics() {
      return CHARACTERISTICS;
    }

    @Override
    public long estimateSize() {
      return Long.MAX_VALUE;
    }

    @Override
    public @Nullable Spliterator<T> trySplit() {
      return null;
    }

    @Override
    public boolean tryAdvance(final Consumer<? super T> action) throws ParsingException {
      try {
        XMLEvent targetEvent = null;
        while ((reader.hasNext()) && (targetEvent == null)) {
          final XMLEvent event = reader.nextEvent();

          if ((event.isEndElement()) || (event.isEndDocument())) { // If this isn't the start of something new, and is the end of the parent element hosting our targetParser's elements, then we're done!
            reader.close(); // Clean up after ourselves.
            return false;
          }

          final Optional<? extends ContentParser<?,?>> parser = parentContext.getParser().findChildParserFor(event);
          if (parser.isPresent()) {
            if (parser.get() == targetParser) { // There could be some other content before the next applicable target event.
              targetEvent = event;
            } else { // If they supplied a parser for this, so use it.
              parser.get().parse(parentContext, event, reader, null);
            }
          } else {
            ignoreEvent(event, reader);
          }
        }
        if (targetEvent == null) return false;
        action.accept(targetParser.parse(parentContext, targetEvent, reader, null));
        return true;
      } catch (XMLStreamException xmlse) {
        try {
          reader.close();
        } catch (XMLStreamException xmlse2) {}
        throw new XMLStreamParsingException(xmlse);
      }
    }

  } // TargetValueSpliterator

  /**
   * The base class for an {@link Exception} indicating some problem was encountered during
   * {@linkplain XMLStreamParser#parse(InputStream) parsing}. Instances will normally either be an
   * {@link XMLStreamParsingException} or {@link ElementValueParsingException}.
   */
  public abstract static class ParsingException extends RuntimeException {

    protected ParsingException() {
      super();
      return;
    }

    protected ParsingException(final Throwable cause) {
      super(cause);
      return;
    }

    protected ParsingException(final String message, final Throwable cause) {
      super(message, cause);
      return;
    }

  } // ParsingException

  /**
   * A {@link ParsingException ParsingException} which indicates an {@link XMLStreamException} was encountered during
   * {@linkplain XMLStreamParser#parse(InputStream) parsing}.
   */
  public static class XMLStreamParsingException extends ParsingException {

    protected XMLStreamParsingException(final XMLStreamException xse) {
      super(Objects.requireNonNull(xse, "null cause"));
      return;
    }

    @Override
    public XMLStreamException getCause() {
      return Objects.requireNonNull(XMLStreamException.class.cast(super.getCause()));
    }

  } // XMLStreamParsingException

  /**
   * A {@link ParsingException ParsingException} associated with an {@link #getElementParsingContext()
   * ElementParsingContext}.
   */
  public abstract static class ElementParsingContextException extends ParsingException {
    protected final ElementParser<?>.ParsingContextImpl context;

    protected ElementParsingContextException(final ElementParser<?>.ParsingContextImpl context) {
      super();
      this.context = context;
      return;
    }

    protected ElementParsingContextException(final Throwable cause, final ElementParser<?>.ParsingContextImpl context) {
      super(cause);
      this.context = context;
      return;
    }

    protected ElementParsingContextException(final String message, final Throwable cause, final ElementParser<?>.ParsingContextImpl context) {
      super(message, cause);
      this.context = context;
      return;
    }

    /**
     * Get the {@link XMLStreamParser.ElementParsingContext ElementParsingContext} when this exception occurred.
     * 
     * @return The {@link XMLStreamParser.ElementParsingContext ElementParsingContext} when this exception occurred.
     */
    public ElementParsingContext<?> getElementParsingContext() {
      return context;
    }

  } // ElementParsingContextException

  /**
   * A {@link ParsingException ParsingException} which indicates a problem occurred while mapping the
   * {@linkplain XMLStreamParser#parse(InputStream) parsed} XML into a {@linkplain XMLStreamParser#getTargetValueClass()
   * target value}.
   */
  public static class ElementValueParsingException extends ElementParsingContextException {

    protected ElementValueParsingException(final Exception cause, final ElementParser<?>.ParsingContextImpl context) {
      super(cause.getClass().getName() + " parsing '" + context.getStartElement().getName().getLocalPart() + "' element: " + cause.getMessage(), Objects.requireNonNull(cause, "null cause"), context);
      return;
    }

    @Override
    public Exception getCause() {
      return Objects.requireNonNull(Exception.class.cast(super.getCause()));
    }

  } // ElementValueParsingException

  /**
   * This exception is thrown to abort
   * {@linkplain XMLStreamParser.ContentParser#parse(XMLStreamParser.ElementParser.ParsingContextImpl, XMLEvent, XMLEventReader, ElementParser)
   * parsing} and return the current {@linkplain #getParsingContextImpl() parsing context} when a specified
   * {@linkplain XMLStreamParser.ElementParser element} is encountered.
   */
  private static class TerminatingParserException extends ElementParsingContextException {

    public TerminatingParserException(final ElementParser<?>.ParsingContextImpl context) {
      super(context);
      return;
    }

    public ElementParser<?>.ParsingContextImpl getParsingContextImpl() {
      return context;
    }

  } // TerminatingParserException

  protected static abstract class ContentParser<E extends XMLEvent,@NonNull T> implements Serializable {
    protected final Class<E> eventClass;
    protected final Class<T> targetValueClass;

    protected ContentParser(final Class<E> eventClass, final Class<T> targetValueClass) {
      this.eventClass = eventClass;
      this.targetValueClass = targetValueClass;
      return;
    }

    public final Class<E> getEventClass() {
      return eventClass;
    }

    public final Class<T> getTargetValueClass() {
      return targetValueClass;
    }

    protected boolean isParserFor(final XMLEvent event) {
      return eventClass.isInstance(event);
    }

    /**
     * Process the supplied event, including reading in any and all subsequent events which constitute a part of it's
     * content (e.g. all child nodes for a given element, up to and including it's end element), but <em>not</em>
     * consuming any subsequent events for nodes which are peers of the supplied event.
     * 
     * @param parentContext The context associated with parsing of the current document.
     * @param event The event to be processed.
     * @param reader The reader to read any content from.
     * @param terminatingParser Throw a TerminatingParserException if an element using this parser is encountered.
     * @return The value created from the supplied event and it's content.
     * @throws ParseException If there was a problem parsing.
     */
    protected abstract T parse(final ElementParser<?>.@Nullable ParsingContextImpl parentContext, final XMLEvent event, final XMLEventReader reader, final @Nullable ElementParser<?> terminatingParser) throws ParsingException;

  } // ContentParser

  protected static class CharactersParser extends ContentParser<Characters,String> {
    private final boolean includeWhiteSpace;
    private final boolean includeCData;
    private final boolean includeIgnorableWhiteSpace;

    public CharactersParser(final boolean includeWhiteSpace, final boolean includeCData, final boolean includeIgnorableWhiteSpace) {
      super(Characters.class, String.class);
      this.includeWhiteSpace = includeWhiteSpace;
      this.includeCData = includeCData;
      this.includeIgnorableWhiteSpace = includeIgnorableWhiteSpace;
      return;
    }

    @Override
    protected final boolean isParserFor(final XMLEvent event) {
      if (!super.isParserFor(event)) return false;
      final Characters characters = event.asCharacters();
      return ((includeWhiteSpace) || (!characters.isWhiteSpace())) && ((includeCData) || (!characters.isCData())) && ((includeIgnorableWhiteSpace) || (!characters.isIgnorableWhiteSpace()));
    }

    @Override
    protected final String parse(final ElementParser<?>.@Nullable ParsingContextImpl parentContext, final XMLEvent event, final XMLEventReader reader, final @Nullable ElementParser<?> terminatingParser) throws ParsingException {
      return event.asCharacters().getData();
    }

  } // CharactersParser

  protected static class ElementParser<@NonNull T> extends ContentParser<StartElement,T> {
    @SuppressWarnings("unchecked")
    public static final Class<ElementParser<@NonNull ?>> WILDCARD_CLASS = (Class<ElementParser<@NonNull ?>>)(Object)ElementParser.class;
    protected final QName elementName;
    private final Function<ElementParsingContext<T>,T> targetValueFunction;
    protected final boolean saveTargetValue;
    private final Set<? extends ContentParser<?,?>> childParsers;

    public ElementParser(final Class<T> targetValueClass, final QName elementName, final Function<ElementParsingContext<T>,T> targetValueFunction, final boolean saveTargetValue, final @Nullable Collection<? extends ContentParser<?,?>> childParsers) {
      super(StartElement.class, targetValueClass);
      this.elementName = elementName;
      this.targetValueFunction = targetValueFunction;
      this.saveTargetValue = saveTargetValue;
      this.childParsers = (childParsers != null) ? Collections.unmodifiableSet(new CopyOnWriteArraySet<>(childParsers)) : Collections.emptySet();
      return;
    }

    public ElementParser(final Class<T> targetValueClass, final QName elementName, final Function<ElementParsingContext<T>,T> targetValueFunction, final boolean saveTargetValue, final @NonNull ContentParser<?,?> @Nullable... childParsers) {
      this(targetValueClass, elementName, targetValueFunction, saveTargetValue, (childParsers != null) ? Arrays.asList(childParsers) : null);
      return;
    }

    public final QName getElementName() {
      return elementName;
    }

    public final Set<? extends ContentParser<?,?>> getChildParsers() {
      return childParsers;
    }

    @Override
    protected final boolean isParserFor(final XMLEvent event) {
      if (!super.isParserFor(event)) return false;
      return elementName.equals(event.asStartElement().getName());
    }

    protected final Optional<? extends ContentParser<?,?>> findChildParserFor(final @Nullable XMLEvent event) {
      return (event != null) ? childParsers.stream().filter((parser) -> parser.isParserFor(event)).findFirst() : Optional.empty();
    }

    @Override
    protected final T parse(final ElementParser<?>.@Nullable ParsingContextImpl parentContext, final XMLEvent event, final XMLEventReader reader, final @Nullable ElementParser<?> terminatingParser) throws ParsingException {
      final ParsingContextImpl context = (parentContext != null) ? new ParsingContextImpl(parentContext, eventClass.cast(event)) : new ParsingContextImpl(eventClass.cast(event));
      context.parseChildren(reader, terminatingParser);
      final T targetValue;
      try {
        targetValue = targetValueFunction.apply(context);
      } catch (ElementValueParsingException evpe) {
        throw evpe;
      } catch (RuntimeException re) {
        throw new ElementValueParsingException(re, context);
      }
      if (saveTargetValue) context.saveValue(targetValue);
      return targetValue;
    }

    @Override
    public String toString() {
      return '<' + elementName.toString() + '>';
    }

    private static final Stream<Map.Entry<ElementParser<?>,List<Object>>> getElementParserEntries(final Stream<? extends Map.Entry<? extends ContentParser<?,?>,List<Object>>> values) {
      return values.filter((entry) -> ElementParser.class.isInstance(entry.getKey())).<Map.Entry<ElementParser<?>,List<Object>>> map((entry) -> new AbstractMap.SimpleImmutableEntry<>(ElementParser.class.cast(entry.getKey()), entry.getValue()));
    }

    private static final <@NonNull ET> Stream<ET> getElementValues(final Stream<? extends Map.Entry<? extends ContentParser<?,?>,List<Object>>> values, final @Nullable QName elementName, final Class<ET> targetValueClass) {
      return getElementParserEntries(values).filter((entry) -> (elementName == null) || elementName.equals(entry.getKey().getElementName())).filter((entry) -> targetValueClass.isAssignableFrom(entry.getKey().getTargetValueClass())).map(Map.Entry::getValue).flatMap(List::stream).map(targetValueClass::cast);
    }

    public final class ParsingContextImpl implements ElementParsingContext<T> {
      private final Map<ElementParser<?>,List<Object>> savedValues; // This is a reference to a singleton Map of saved values, shared by the entire context tree.
      private final Map<ContentParser<?,?>,List<Object>> childValues = new ConcurrentHashMap<>();
      private final ElementParser<?>.@Nullable ParsingContextImpl parentContext;
      private final StartElement startElement;

      public ParsingContextImpl(final StartElement startElement) {
        savedValues = new ConcurrentHashMap<>();
        parentContext = null;
        this.startElement = startElement;
        return;
      }

      public ParsingContextImpl(final ElementParser<?>.ParsingContextImpl parentContext, final StartElement startElement) {
        savedValues = parentContext.savedValues;
        this.parentContext = parentContext;
        this.startElement = startElement;
        return;
      }

      public ElementParser<T> getParser() {
        return ElementParser.this;
      }

      @Override
      public Class<T> getTargetValueClass() {
        return targetValueClass;
      }

      @Override
      public final QName getElementName() {
        return elementName;
      }

      @Override
      public StartElement getStartElement() {
        return startElement;
      }

      private int getElementDepth() {
        final ElementParser<?>.@Nullable ParsingContextImpl pc = parentContext;
        if (pc == null) return 0;
        return pc.getElementDepth() + 1;
      }

      private Deque<StartElement> getElementContextImpl(Deque<StartElement> elementStack) {
        if (parentContext != null) parentContext.getElementContextImpl(elementStack);
        elementStack.push(startElement);
        return elementStack;
      }

      @Override
      public Deque<StartElement> getElementContext() {
        return getElementContextImpl(new ArrayDeque<>(getElementDepth() + 1));
      }

      private XMLEvent getNextEvent(final XMLEventReader reader, final @Nullable ElementParser<?> terminatingParser) throws ParsingException {
        try {
          if ((terminatingParser != null) && (findChildParserFor(reader.peek()).equals(Optional.of(terminatingParser)))) throw new TerminatingParserException(this);
          return reader.nextEvent();
        } catch (XMLStreamException xse) {
          throw new XMLStreamParsingException(xse);
        }
      }

      protected void parseChildren(final XMLEventReader reader, final @Nullable ElementParser<?> terminatingParser) throws ParsingException {
        XMLEvent event = getNextEvent(reader, terminatingParser);
        while (!event.isEndElement()) {
          final Optional<? extends ContentParser<?,?>> parser = findChildParserFor(event);
          if (parser.isPresent()) {
            final Object childValue = parser.get().parse(this, parser.get().eventClass.cast(event), reader, terminatingParser);
            final List<Object> existingValues = childValues.get(parser.get());
            if (existingValues != null) {
              existingValues.add(childValue);
            } else {
              childValues.put(parser.get(), new CopyOnWriteArrayList<>(Collections.singleton(childValue)));
            }
          } else { // Ignore any content the user didn't specify a parser for...
            ignoreEvent(event, reader);
          }
          event = getNextEvent(reader, terminatingParser);
        }
        return;
      }

      public void saveValue(final T value) {
        final List<Object> existingValues = savedValues.get(ElementParser.this);
        if (existingValues != null) {
          existingValues.add(value);
        } else {
          savedValues.put(ElementParser.this, new CopyOnWriteArrayList<>(Collections.singleton(value)));
        }
        return;
      }

      public <@NonNull S> Stream<S> getSavedValues(final ElementParser<S> savedElementParser) {
        final List<Object> values = savedValues.get(ElementParser.this);
        return (values != null) ? values.stream().map(savedElementParser.getTargetValueClass()::cast) : Stream.empty();
      }

      public <@NonNull S> S getRequiredSavedValue(final ElementParser<S> savedElementParser) throws NoSuchElementException {
        return getSavedValues(savedElementParser).findFirst().orElseThrow(() -> new NoSuchElementException(ElementParser.this.toString()));
      }

      @Override
      public <@NonNull S> Stream<S> getSavedValues(final @Nullable QName savedElementName, final Class<S> targetValueClass) {
        return getElementValues(savedValues.entrySet().stream(), savedElementName, targetValueClass);
      }

      public <@NonNull ET> Stream<ET> getChildValues(final ContentParser<?,ET> childParser) {
        return Optional.ofNullable(childValues.get(childParser)).map(List::stream).orElse(Stream.empty()).map((v) -> childParser.getTargetValueClass().cast(v));
      }

      public <@NonNull ET> Optional<ET> getOptionalChildValue(final ContentParser<?,ET> childParser) {
        return getChildValues(childParser).findFirst();
      }

      public <@NonNull ET> ET getRequiredChildValue(final ContentParser<?,ET> childParser) throws NoSuchElementException {
        return getOptionalChildValue(childParser).orElseThrow(() -> new NoSuchElementException(childParser.toString()));
      }

      @Override
      public Stream<Map.Entry<Map.Entry<QName,Class<?>>,List<?>>> getChildValues() {
        return getElementParserEntries(childValues.entrySet().stream()).map((entry) -> new AbstractMap.SimpleImmutableEntry<>(new AbstractMap.SimpleImmutableEntry<>(entry.getKey().getElementName(), entry.getKey().getTargetValueClass()), entry.getValue()));
      }

    } // ElementParser.ParsingContextImpl

  } // ElementParser

  protected static class ContainerElementParser extends ElementParser<StartElement> {

    public ContainerElementParser(final QName elementName, final @NonNull ElementParser<?> @Nullable... childElementParsers) {
      super(StartElement.class, elementName, (context) -> context.getStartElement(), false, childElementParsers);
      return;
    }

  } // ContainerElementParser

  protected static class WrapperElementParser<@NonNull T> extends ElementParser<T> {

    public WrapperElementParser(final QName elementName, final ElementParser<T> wrappedElementParser) {
      super(wrappedElementParser.targetValueClass, elementName, (ctx) -> cast(ctx).getRequiredChildValue(wrappedElementParser), false, wrappedElementParser);
      return;
    }

  } // WrapperElementParser

  protected static class SimpleElementParser<@NonNull T> extends ElementParser<T> {
    private static final CharactersParser CHARACTERS_PARSER = new CharactersParser(true, true, false);

    public SimpleElementParser(final Class<T> targetValueClass, final QName elementName, final BiFunction<ElementParsingContext<T>,? super String,? extends T> targetValueFunction, final boolean saveTargetValue) {
      super(targetValueClass, elementName, (ctx) -> targetValueFunction.apply(ctx, cast(ctx).getChildValues(CHARACTERS_PARSER).collect(Collectors.joining())), saveTargetValue, CHARACTERS_PARSER);
      return;
    }

    public SimpleElementParser(final Class<T> targetValueClass, final QName elementName, final Function<? super String,? extends T> targetValueFunction, final boolean saveTargetValue) {
      this(targetValueClass, elementName, (ctx, value) -> targetValueFunction.apply(value), saveTargetValue);
      return;
    }

  } // SimpleElementParser

  protected static class StringElementParser extends SimpleElementParser<String> {

    public StringElementParser(final QName elementName, final boolean saveTargetValue) {
      super(String.class, elementName, Function.identity(), saveTargetValue);
      return;
    }

  } // StringElementParser

  protected static class InjectedTargetElementParser<@NonNull T> extends ElementParser<T> {

    public InjectedTargetElementParser(final Class<T> targetValueClass, final Class<? extends T> targetImplClass, final QName elementName, final boolean saveTargetValue, final @Nullable Collection<? extends ElementParser<?>> childElementParsers, final @Nullable Map<String,Function<ElementParsingContext<T>,@Nullable Object>> injectionSpecs) throws IllegalArgumentException {
      super(targetValueClass, elementName, (ctx) -> ctx.getInjectedValue(targetImplClass, injectionSpecs), saveTargetValue, childElementParsers);
      return;
    }

  } // InjectedTargetElementParser

  /**
   * <p>
   * This class allows you to define the elements used within your XML documents and then
   * {@linkplain #createParser(Class, QName, QName) create a parser} for them.
   * </p>
   * 
   * <p>
   * XML uses a tree structure, and you use this class to define the schema for your XML from the bottom up, starting
   * with defining the "leaf" nodes, and then
   * {@linkplain XMLStreamParser.SchemaBuilder.ChildElementListBuilder#addChild(QName, Class) referencing} those when
   * defining the parent elements which utilize those leaves as their own children, and so on and so forth, working your
   * way up to the root document element. All element definitions map their content to some type of
   * {@linkplain XMLStreamParser#getTargetValueClass() target value}. This builder class allows you to define several
   * types of elements within your schema. The first are low-level leaf-type
   * {@linkplain #defineSimpleElement(String, Class, BiFunction, boolean) simple} elements, and the generic
   * {@linkplain #defineStringElement(String, boolean) string} version. Next, the most common, are
   * {@linkplain #defineElement(String, Class, Function) typed elements}, for binding to your own data model. And then,
   * finally, are {@linkplain #defineWrapperElement(String, QName, Class) wrapper} and
   * {@linkplain #defineContainerElementWithChildBuilder(String) container} elements, for housing the others and forming
   * the root of the document tree.
   * </p>
   * 
   * <p>
   * Any elements encountered while parsing which have not been defined within your schema will be silently ignored.
   * </p>
   *
   * <p>
   * All definition methods accept a {@linkplain QName#getLocalPart() local name}, where the element will be defined
   * using the {@linkplain #getNamespace() current namespace} of the builder, which, unless you have
   * {@linkplain #setNamespace(URI) changed} it, will be the URI specified when you
   * {@linkplain XMLStreamParser#buildSchema(URI) created} it.
   * </p>
   * 
   * <p>
   * Note that this API doesn't provide any way to define elements having mixed content, containing both
   * {@linkplain #defineSimpleElement(String, Class, BiFunction, boolean) child character data} and
   * {@link #defineElementWithChildBuilder(String, Class, Function, boolean) child elements}. If your schema requires
   * parsing those, you will need to write your own XMLStreamParser subclass and define those elements using the
   * internal API.
   * </p>
   * 
   * @param <SB> The concrete class of schema builder being used.
   */
  public static class SchemaBuilder<@NonNull SB extends SchemaBuilder<@NonNull ?>> {
    protected final Class<? extends SB> schemaBuilderType;
    protected final @Nullable URI namespace;
    protected final Set<ElementParser<?>> elementParsers;

    protected SchemaBuilder(final Class<? extends SB> schemaBuilderType, final @Nullable URI namespace, final @Nullable Set<ElementParser<?>> elementParsers, final boolean unmodifiable) {
      this.schemaBuilderType = Objects.requireNonNull(schemaBuilderType);
      this.namespace = namespace;
      final Set<ElementParser<?>> copy = (elementParsers != null) ? new CopyOnWriteArraySet<>(elementParsers) : new CopyOnWriteArraySet<>();
      this.elementParsers = (unmodifiable) ? Collections.unmodifiableSet(copy) : copy;
      return;
    }

    protected SB forkImpl(final @Nullable URI namespace, final boolean unmodifiable) {
      return schemaBuilderType.cast(new SchemaBuilder<SB>(schemaBuilderType, namespace, elementParsers, unmodifiable));
    }

    /**
     * Create a copy of this schema and all the element definitions it currently contains.
     * 
     * @return A new builder instance, populated with all the elements from this schema. You will be able to modify the
     * returned schema, even if this one is {@linkplain #unmodifiable() unmodifiable}.
     */
    public final SB fork() {
      return forkImpl(namespace, false);
    }

    /**
     * Create an {@linkplain Collections#unmodifiableSet(Set) unmodifiable} {@linkplain #fork() fork} of this schema.
     * 
     * @return A copy of this builder which can't be modified. If you subsequently wish to make changes to the
     * unmodifiable schema, you can {@linkplain #fork() fork} it again.
     * @see #fork()
     */
    public final SB unmodifiable() {
      return forkImpl(namespace, true);
    }

    /**
     * Get the {@linkplain QName#getNamespaceURI() namespace} this builder is using to define elements. Note that this
     * property is <em>immutable</em>.
     * 
     * @return The current {@linkplain QName#getNamespaceURI() namespace}.
     * @see #setNamespace(URI)
     */
    public final Optional<URI> getNamespace() {
      return Optional.ofNullable(namespace);
    }

    /**
     * Continue building on this schema, but using the supplied {@linkplain QName#getNamespaceURI() namespace}.
     * 
     * @param namespace The {@linkplain QName#getNamespaceURI() namespace} you wish subsequent definitions on the
     * returned builder to use.
     * @return If the supplied namespace is the same as the {@linkplain #getNamespace() current} one, this builder will
     * be returned, otherwise a {@linkplain #fork() forked} copy using the supplied namespace will be returned.
     * @see #getNamespace()
     * @see #fork()
     */
    public final SB setNamespace(final @Nullable URI namespace) {
      if (Objects.equals(this.namespace, namespace)) return schemaBuilderType.cast(this);
      return forkImpl(namespace, false);
    }

    protected final QName qn(final String localName) {
      return new QName(Optional.ofNullable(namespace).map(URI::toString).orElse(XMLConstants.NULL_NS_URI), localName);
    }

    protected final @NonNull QName[] qns(final @NonNull String @Nullable... localNames) {
      return ((localNames != null) ? Arrays.<String> asList(localNames) : Collections.<String> emptyList()).stream().map(this::qn).toArray((n) -> new @NonNull QName[n]);
    }

    protected SB addParser(final ElementParser<?> elementParser) {
      elementParsers.add(elementParser);
      return schemaBuilderType.cast(this);
    }

    protected final <@NonNull ET,PT extends ElementParser<?>> PT getParser(final Class<PT> ofParserType, final QName forElementName, final Class<ET> forElementTargetValueClass) throws NoSuchElementException {
      final List<ElementParser<?>> parsers = elementParsers.stream().filter((parser) -> ofParserType.isAssignableFrom(parser.getClass())).filter((parser) -> forElementName.equals(parser.getElementName())).filter((parser) -> forElementTargetValueClass.isAssignableFrom(parser.getTargetValueClass())).collect(Collectors.toList());
      if (parsers.isEmpty()) throw new NoSuchElementException("No '" + forElementName.getLocalPart() + "' element found with '" + forElementTargetValueClass + "' target value class");
      if (parsers.size() > 1) throw new NoSuchElementException("Multiple '" + forElementName.getLocalPart() + "' elements found with '" + forElementTargetValueClass + "' target value class");
      return ofParserType.cast(parsers.get(0));
    }

    @SuppressWarnings("unchecked")
    protected final <@NonNull ET> ElementParser<ET> getParser(final QName forElementName, final Class<ET> forElementTargetValueClass) throws NoSuchElementException {
      return getParser((Class<ElementParser<ET>>)(Object)ElementParser.class, forElementName, forElementTargetValueClass);
    }

    protected final <PT extends ElementParser<@NonNull ?>> PT getParser(final Class<PT> ofParserType, final QName forElementName) throws NoSuchElementException {
      final List<ElementParser<?>> parsers = elementParsers.stream().filter((parser) -> ofParserType.isAssignableFrom(parser.getClass())).filter((parser) -> forElementName.equals(parser.getElementName())).collect(Collectors.toList());
      if (parsers.isEmpty()) throw new NoSuchElementException("No '" + forElementName.getLocalPart() + "' element found");
      if (parsers.size() > 1) throw new NoSuchElementException("Multiple '" + forElementName.getLocalPart() + "' elements found");
      return ofParserType.cast(parsers.get(0));
    }

    protected final ElementParser<@NonNull ?> getParser(final QName forElementName) throws NoSuchElementException {
      return getParser(ElementParser.WILDCARD_CLASS, forElementName);
    }

    /**
     * <p>
     * Import an element definition from another {@link XMLStreamParser.SchemaBuilder SchemaBuilder} to this one.
     * </p>
     * 
     * <p>
     * Importing a definition will <em>not</em> alter the namespace the element was originally defined in, or any of
     * it's other attributes. The imported definition will retain any child elements it was created with, though only
     * the imported element itself will be available for
     * {@linkplain XMLStreamParser.SchemaBuilder.ChildElementListBuilder#addChild(QName, Class) reference} by other
     * elements being defined within this schema, whereas the imported element's children will <em>not</em> be available
     * to be referenced directly within this schema.
     * </p>
     * 
     * @param <ET> The type of target value produced by the imported element.
     * @param fromSchemaBuilder The source schema to import the element from.
     * @param elementName The name of the element definition you wish to import.
     * @param targetValueClass The {@link Class} object representing the target value produced by the definition you
     * wish to import.
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @throws NoSuchElementException If the referenced element hasn't been defined in the <em>fromSchemaBuilder</em>.
     * @see #importElementDefinition(XMLStreamParser.SchemaBuilder, String, Class)
     * @see #importElementDefinition(XMLStreamParser.SchemaBuilder, QName)
     * @see #importElementDefinition(XMLStreamParser.SchemaBuilder, String)
     */
    public final <@NonNull ET> SB importElementDefinition(final SchemaBuilder<?> fromSchemaBuilder, final QName elementName, final Class<ET> targetValueClass) throws NoSuchElementException {
      return addParser(fromSchemaBuilder.getParser(elementName, targetValueClass));
    }

    /**
     * <p>
     * Import an element definition from another {@link XMLStreamParser.SchemaBuilder SchemaBuilder} to this one.
     * </p>
     * 
     * <p>
     * Importing a definition will <em>not</em> alter the namespace the element was originally defined in, or any of
     * it's other attributes. The imported definition will retain any child elements it was created with, though only
     * the imported element itself will be available for
     * {@linkplain XMLStreamParser.SchemaBuilder.ChildElementListBuilder#addChild(QName, Class) reference} by other
     * elements being defined within this schema, whereas the imported element's children will <em>not</em> be available
     * to be referenced directly within this schema.
     * </p>
     * 
     * @param <ET> The type of target value produced by the imported element.
     * @param fromSchemaBuilder The source schema to import the element from.
     * @param elementLocalName The {@linkplain QName#getLocalPart() local name} of the element definition you wish to
     * import (the {@linkplain #getNamespace() current namespace} for the <em>fromSchemaBuilder</em> will be used).
     * @param targetValueClass The {@link Class} object representing the target value produced by the definition you
     * wish to import.
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @throws NoSuchElementException If the referenced element hasn't been defined in the <em>fromSchemaBuilder</em>.
     * @see #importElementDefinition(XMLStreamParser.SchemaBuilder, QName, Class)
     * @see #importElementDefinition(XMLStreamParser.SchemaBuilder, QName)
     * @see #importElementDefinition(XMLStreamParser.SchemaBuilder, String)
     */
    public final <@NonNull ET> SB importElementDefinition(final SchemaBuilder<?> fromSchemaBuilder, final String elementLocalName, final Class<ET> targetValueClass) throws NoSuchElementException {
      return importElementDefinition(fromSchemaBuilder, fromSchemaBuilder.qn(elementLocalName), targetValueClass);
    }

    /**
     * <p>
     * Import an element definition from another {@link XMLStreamParser.SchemaBuilder SchemaBuilder} to this one.
     * </p>
     * 
     * <p>
     * Importing a definition will <em>not</em> alter the namespace the element was originally defined in, or any of
     * it's other attributes. The imported definition will retain any child elements it was created with, though only
     * the imported element itself will be available for
     * {@linkplain XMLStreamParser.SchemaBuilder.ChildElementListBuilder#addChild(QName, Class) reference} by other
     * elements being defined within this schema, whereas the imported element's children will <em>not</em> be available
     * to be referenced directly within this schema.
     * </p>
     * 
     * @param fromSchemaBuilder The source schema to import the element from.
     * @param elementName The name of the element definition you wish to import.
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @throws NoSuchElementException If the referenced element hasn't been defined in the <em>fromSchemaBuilder</em>.
     * @see #importElementDefinition(XMLStreamParser.SchemaBuilder, QName, Class)
     * @see #importElementDefinition(XMLStreamParser.SchemaBuilder, String, Class)
     * @see #importElementDefinition(XMLStreamParser.SchemaBuilder, String)
     */
    public final SB importElementDefinition(final SchemaBuilder<?> fromSchemaBuilder, final QName elementName) throws NoSuchElementException {
      return addParser(fromSchemaBuilder.getParser(elementName));
    }

    /**
     * <p>
     * Import an element definition from another {@link XMLStreamParser.SchemaBuilder SchemaBuilder} to this one.
     * </p>
     * 
     * <p>
     * Importing a definition will <em>not</em> alter the namespace the element was originally defined in, or any of
     * it's other attributes. The imported definition will retain any child elements it was created with, though only
     * the imported element itself will be available for
     * {@linkplain XMLStreamParser.SchemaBuilder.ChildElementListBuilder#addChild(QName, Class) reference} by other
     * elements being defined within this schema, whereas the imported element's children will <em>not</em> be available
     * to be referenced directly within this schema.
     * </p>
     * 
     * @param fromSchemaBuilder The source schema to import the element from.
     * @param elementLocalName The {@linkplain QName#getLocalPart() local name} of the element definition you wish to
     * import (the {@linkplain #getNamespace() current namespace} for the <em>fromSchemaBuilder</em> will be used).
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @throws NoSuchElementException If the referenced element hasn't been defined in the <em>fromSchemaBuilder</em>.
     * @see #importElementDefinition(XMLStreamParser.SchemaBuilder, QName, Class)
     * @see #importElementDefinition(XMLStreamParser.SchemaBuilder, String, Class)
     * @see #importElementDefinition(XMLStreamParser.SchemaBuilder, QName)
     */
    public final SB importElementDefinition(final SchemaBuilder<?> fromSchemaBuilder, final String elementLocalName) throws NoSuchElementException {
      return importElementDefinition(fromSchemaBuilder, fromSchemaBuilder.qn(elementLocalName));
    }

    /**
     * <p>
     * Define a "simple" element, of the form '<code>&lt;ElementName&gt;Value&lt;/ElementName&gt;</code>', containing
     * only {@linkplain Characters character data}, and no child elements. A simple element can be defined to calculate
     * whatever target value type you choose (via the supplied <code>targetValueFunction</code>) when parsed.
     * </p>
     * 
     * <p>
     * If you <em>don't</em> want to use the parsed character data {@link String} to calculate some other target value
     * type, you should define your element using the {@linkplain #defineStringElement(String, boolean) string method}.
     * You should also be using a different method to define your element if it
     * {@linkplain #defineElement(String, Class, Function) contains no child data}, or
     * {@linkplain #defineElementWithChildBuilder(String, Class, Function, boolean) contains child elements}.
     * </p>
     * 
     * @param <ET> The type of target value which will be provided when the defined element is parsed.
     * @param simpleElementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined (the
     * {@linkplain #getNamespace() current namespace} will be used).
     * @param targetValueClass The {@link Class} object for the type of target value which will be constructed when the
     * defined element is parsed.
     * @param targetValueFunction A {@link BiFunction} to be used to calculate the target value for the defined element
     * whenever it's encountered by the parser. This function accepts the current
     * {@link XMLStreamParser.ElementParsingContext ElementParsingContext} and a {@link String} containing the child
     * {@linkplain Characters character data}, and must return the calculated target value for the parsed element.
     * @param saveTargetValue Should target values calculated for the defined element be saved by the parser and then
     * made available (via the {@link XMLStreamParser.ElementParsingContext ElementParsingContext}) to the target value
     * calculation functions of all subsequent elements parsed within the current document?
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @see #defineSimpleElement(String, Class, Function)
     */
    public final <@NonNull ET> SB defineSimpleElement(final String simpleElementLocalName, final Class<ET> targetValueClass, final BiFunction<ElementParsingContext<ET>,String,ET> targetValueFunction, final boolean saveTargetValue) {
      return addParser(new SimpleElementParser<ET>(targetValueClass, qn(simpleElementLocalName), targetValueFunction, saveTargetValue));
    }

    /**
     * <p>
     * Define a "simple" element, of the form '<code>&lt;ElementName&gt;Value&lt;/ElementName&gt;</code>', containing
     * only {@linkplain Characters character data}, and no child elements. A simple element can be defined to calculate
     * whatever target value type you choose (via the supplied <code>targetValueFunction</code>) when parsed.
     * </p>
     * 
     * <p>
     * If you <em>don't</em> want to use the parsed character data {@link String} to calculate some other target value
     * type, you should define your element using the {@linkplain #defineStringElement(String, boolean) string method}.
     * You should also be using a different method to define your element if it
     * {@linkplain #defineElement(String, Class, Function) contains no child data}, or
     * {@linkplain #defineElementWithChildBuilder(String, Class, Function, boolean) contains child elements}.
     * </p>
     * 
     * @param <ET> The type of target value which will be provided when the defined element is parsed.
     * @param simpleElementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined (the
     * {@linkplain #getNamespace() current namespace} will be used).
     * @param targetValueClass The {@link Class} object for the type of target value which will be constructed when the
     * defined element is parsed.
     * @param targetValueFunction A {@link Function} to be used to calculate the target value for the defined element
     * whenever it's encountered by the parser. This function accepts a {@link String} containing the child
     * {@linkplain Characters character data}, and must return the calculated target value for the parsed element.
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @see #defineSimpleElement(String, Class, BiFunction, boolean)
     */
    public final <@NonNull ET> SB defineSimpleElement(final String simpleElementLocalName, final Class<ET> targetValueClass, final Function<String,ET> targetValueFunction) {
      return addParser(new SimpleElementParser<ET>(targetValueClass, qn(simpleElementLocalName), targetValueFunction, false));
    }

    /**
     * <p>
     * Define a "string" element, of the form '<code>&lt;ElementName&gt;Value&lt;/ElementName&gt;</code>', containing
     * only {@linkplain Characters character} data, and no child elements.
     * </p>
     * 
     * <p>
     * This is a specialization of the {@linkplain #defineSimpleElement(String, Class, BiFunction, boolean) simple
     * element}, where instead of supplying a function to calculate a target value from the parsed character data, the
     * target value <em>is</em> simply the parsed character data {@link String}, with no conversion.
     * </p>
     * 
     * @param stringElementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined (the
     * {@linkplain #getNamespace() current namespace} will be used).
     * @param saveTargetValue Should target values calculated for the defined element be saved by the parser and then
     * made available (via the {@link XMLStreamParser.ElementParsingContext ElementParsingContext}) to the target value
     * calculation functions of all subsequent elements parsed within the current document?
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @see #defineStringElement(String)
     */
    public final SB defineStringElement(final String stringElementLocalName, final boolean saveTargetValue) {
      return addParser(new StringElementParser(qn(stringElementLocalName), saveTargetValue));
    }

    /**
     * <p>
     * Define a "string" element, of the form '<code>&lt;ElementName&gt;Value&lt;/ElementName&gt;</code>', containing
     * only {@linkplain Characters character} data, and no child elements.
     * </p>
     * 
     * <p>
     * This is a specialization of the {@linkplain #defineSimpleElement(String, Class, BiFunction, boolean) simple
     * element}, where instead of supplying a function to calculate a target value from the parsed character data, the
     * target value <em>is</em> simply the parsed character data {@link String}, with no conversion.
     * </p>
     * 
     * @param stringElementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined (the
     * {@linkplain #getNamespace() current namespace} will be used).
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @see #defineStringElement(String, boolean)
     */
    public final SB defineStringElement(final String stringElementLocalName) {
      return defineStringElement(stringElementLocalName, false);
    }

    /**
     * <p>
     * Define a regular content element, which calculates a target value using the supplied
     * <code>targetValueFunction</code> when parsed.
     * </p>
     * 
     * <p>
     * This method is for defining elements without any child data, if your element contains any child data, you should
     * be defining it using a different method, such as the method to
     * {@linkplain #defineElementWithChildBuilder(String, Class, Function, boolean) build an element definition
     * containing child elements}, or the method to {@linkplain #defineSimpleElement(String, Class, BiFunction, boolean)
     * define a simple element containing only character data}. Also, you should be using a different method to
     * {@linkplain #defineElementWithInjectedTargetBuilder(String, Class, Class, boolean) define an element producing a
     * target value which can be constructed using injection}.
     * </p>
     * 
     * @param <ET> The type of target value which will be provided when the defined element is parsed.
     * @param elementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined (the
     * {@linkplain #getNamespace() current namespace} will be used).
     * @param targetValueClass The {@link Class} object for the type of target value which will be constructed when the
     * defined element is parsed.
     * @param targetValueFunction A {@link Function} to be used to calculate the target value for the defined element
     * whenever it's encountered by the parser. This function accepts the current
     * {@link XMLStreamParser.ElementParsingContext ElementParsingContext} and must return the calculated target value
     * for the parsed element.
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @see #defineElementWithChildBuilder(String, Class, Function, boolean)
     * @see #defineElementWithInjectedTargetBuilder(String, Class, Class, boolean)
     * @see #defineSimpleElement(String, Class, BiFunction, boolean)
     */
    public final <@NonNull ET> SB defineElement(final String elementLocalName, final Class<ET> targetValueClass, final Function<ElementParsingContext<ET>,ET> targetValueFunction) {
      return addParser(new ElementParser<ET>(targetValueClass, qn(elementLocalName), targetValueFunction, false));
    }

    /**
     * <p>
     * Define a regular content element, which calculates a target value using the supplied
     * <code>targetValueFunction</code> when parsed.
     * </p>
     * 
     * <p>
     * This method is for defining elements which have child elements, if your element contains no child elements, you
     * should be defining it using a different method, such as the method to
     * {@linkplain #defineElement(String, Class, Function) define an element with no child data}, or the method to
     * {@linkplain #defineSimpleElement(String, Class, BiFunction, boolean) define a simple element containing only
     * character data}. Also, you should be using a different method to
     * {@linkplain #defineElementWithInjectedTargetBuilder(String, Class, Class, boolean) define an element producing a
     * target value which can be constructed using injection}.
     * </p>
     * 
     * @param <ET> The type of target value which will be provided when the defined element is parsed.
     * @param elementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined (the
     * {@linkplain #getNamespace() current namespace} will be used).
     * @param targetValueClass The {@link Class} object for the type of target value which will be constructed when the
     * defined element is parsed.
     * @param targetValueFunction A {@link Function} to be used to calculate the target value for the defined element
     * whenever it's encountered by the parser. This function accepts the current
     * {@link XMLStreamParser.ElementParsingContext ElementParsingContext} and must return the calculated target value
     * for the parsed element.
     * @param saveTargetValue Should target values calculated for the defined element be saved by the parser and then
     * made available (via the {@link XMLStreamParser.ElementParsingContext ElementParsingContext}) to the target value
     * calculation functions of all subsequent elements parsed within the current document?
     * @return A {@link XMLStreamParser.SchemaBuilder.ChildElementListBuilder ChildElementListBuilder} which you can use
     * to define which elements this definition will have as children.
     * @see #defineElement(String, Class, Function)
     * @see #defineElementWithInjectedTargetBuilder(String, Class, Class, boolean)
     * @see #defineSimpleElement(String, Class, BiFunction, boolean)
     */
    public final <@NonNull ET> ChildElementListBuilder<@NonNull ?> defineElementWithChildBuilder(final String elementLocalName, final Class<ET> targetValueClass, final Function<ElementParsingContext<ET>,ET> targetValueFunction, final boolean saveTargetValue) {
      return new ChildElementListBuilder<ElementParser<?>>(ElementParser.WILDCARD_CLASS, (childParsers) -> addParser(new ElementParser<ET>(targetValueClass, qn(elementLocalName), targetValueFunction, saveTargetValue, childParsers)));
    }

    /**
     * <p>
     * Define a content element which automatically constructs it's target value by
     * {@linkplain XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map) injecting} it's parsed data into
     * the specified <code>targetValueClass</code>.
     * </p>
     * 
     * <p>
     * This method is for defining injected elements without any children, if your element contains children, you should
     * be defining it using the method to
     * {@linkplain #defineElementWithInjectedTargetBuilder(String, Class, Class, boolean) build an injected element
     * definition containing child elements}. If you want to construct the target value yourself, without using
     * injection, you should be using the method to {@linkplain #defineElement(String, Class, Function) define an
     * element with no child data}.
     * </p>
     * 
     * @param <ET> The type of target value which will be provided when the defined element is parsed.
     * @param injectedElementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined
     * (the {@linkplain #getNamespace() current namespace} will be used).
     * @param targetValueClass The {@link Class} object for the type of target value which will be produced when the
     * defined element is parsed.
     * @param targetImplClass The {@link Class} of object which should be injected to create the target value. This will
     * either be the <code>targetValueClass</code>, it's subclass, or interface implementation class.
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
     * @see #defineElementWithInjectedTarget(Class)
     * @see #defineElementWithInjectedTargetBuilder(String, Class, Class, boolean)
     * @see #defineElement(String, Class, Function)
     */
    public final <@NonNull ET> SB defineElementWithInjectedTarget(final String injectedElementLocalName, final Class<ET> targetValueClass, final Class<? extends ET> targetImplClass) {
      return addParser(new InjectedTargetElementParser<ET>(targetValueClass, targetImplClass, qn(injectedElementLocalName), false, null, null));
    }

    /**
     * <p>
     * Define a content element which automatically constructs it's target value by
     * {@linkplain XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map) injecting} it's parsed data into
     * the specified <code>targetValueClass</code>.
     * </p>
     * 
     * <p>
     * This method is for defining injected elements without any children, if your element contains children, you should
     * be defining it using the method to
     * {@linkplain #defineElementWithInjectedTargetBuilder(String, Class, Class, boolean) build an injected element
     * definition containing child elements}. If you want to construct the target value yourself, without using
     * injection, you should be using the method to {@linkplain #defineElement(String, Class, Function) define an
     * element with no child data}.
     * </p>
     * 
     * @param <ET> The type of target value which will be provided when the defined element is parsed.
     * @param targetValueClass The {@link Class} object for the type of target value which will be constructed when the
     * defined element is parsed. The {@linkplain Class#getSimpleName() simple name} from this class will also be used
     * as the {@linkplain QName#getLocalPart() local name} of the element being defined (the {@linkplain #getNamespace()
     * current namespace} will be used).
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
     * @see #defineElementWithInjectedTarget(String, Class, Class)
     * @see #defineElementWithInjectedTargetBuilder(String, Class, Class, boolean)
     * @see #defineElement(String, Class, Function)
     */
    public final <@NonNull ET> SB defineElementWithInjectedTarget(final Class<ET> targetValueClass) {
      return defineElementWithInjectedTarget(targetValueClass.getSimpleName(), targetValueClass, targetValueClass);
    }

    /**
     * <p>
     * Define a content element which automatically constructs it's target value by
     * {@linkplain XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map) injecting} it's parsed data into
     * the specified <code>targetValueClass</code>.
     * </p>
     * 
     * <p>
     * This method is for defining injected elements which have children or require attribute type mapping before
     * injection. If this is not the case for the element being defined, you should be using the other method to
     * {@linkplain #defineElementWithInjectedTarget(String, Class, Class) define an injected element without children}.
     * If you want to construct the target value yourself, without using injection, you should be using the method to
     * {@linkplain #defineElementWithChildBuilder(String, Class, Function, boolean) build an element definition
     * containing child elements}.
     * </p>
     * 
     * @param <ET> The type of target value which will be provided when the defined element is parsed.
     * @param injectedElementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined
     * (the {@linkplain #getNamespace() current namespace} will be used).
     * @param targetValueClass The {@link Class} object for the type of target value which will be constructed when the
     * defined element is parsed.
     * @param targetImplClass The {@link Class} of object which should be injected to create the target value. This will
     * either be the <code>targetValueClass</code>, it's subclass, or interface implementation class.
     * @param saveTargetValue Should target values calculated for the defined element be saved by the parser and then
     * made available (via the {@link XMLStreamParser.ElementParsingContext ElementParsingContext}) to the target value
     * calculation functions of all subsequent elements parsed within the current document?
     * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder} which
     * you can use to reference other existing element definitions this one will have as children and specify how those
     * should be injected into the <code>targetValueClass</code>.
     * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
     * @see #defineElementWithInjectedTargetBuilder(String, Class)
     * @see #defineElementWithInjectedTargetBuilder(Class)
     * @see #defineElementWithInjectedTarget(String, Class, Class)
     * @see #defineElementWithChildBuilder(String, Class, Function, boolean)
     */
    public final <@NonNull ET> InjectedTargetElementBuilder<ET,?> defineElementWithInjectedTargetBuilder(final String injectedElementLocalName, final Class<ET> targetValueClass, final Class<? extends ET> targetImplClass, final boolean saveTargetValue) {
      return new InjectedTargetElementBuilder<>(ElementParser.WILDCARD_CLASS, (childParsers, injectionSpecs) -> addParser(new InjectedTargetElementParser<ET>(targetValueClass, targetImplClass, qn(injectedElementLocalName), saveTargetValue, childParsers, injectionSpecs)));
    }

    /**
     * <p>
     * Define a content element which automatically constructs it's target value by
     * {@linkplain XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map) injecting} it's parsed data into
     * the specified <code>targetValueClass</code>.
     * </p>
     * 
     * <p>
     * This method is for defining injected elements which have children or require attribute type mapping before
     * injection. If this is not the case for the element being defined, you should be using the other method to
     * {@linkplain #defineElementWithInjectedTarget(String, Class, Class) define an injected element without children}.
     * If you want to construct the target value yourself, without using injection, you should be using the method to
     * {@linkplain #defineElementWithChildBuilder(String, Class, Function, boolean) build an element definition
     * containing child elements}.
     * </p>
     * 
     * @param <ET> The type of target value which will be provided when the defined element is parsed.
     * @param injectedElementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined
     * (the {@linkplain #getNamespace() current namespace} will be used).
     * @param targetValueClass The {@link Class} object for the type of target value which will be constructed when the
     * defined element is parsed.
     * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder} which
     * you can use to reference other existing element definitions this one will have as children and specify how those
     * should be injected into the <code>targetValueClass</code>.
     * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
     * @see #defineElementWithInjectedTargetBuilder(String, Class, Class, boolean)
     * @see #defineElementWithInjectedTargetBuilder(Class)
     * @see #defineElementWithInjectedTarget(String, Class, Class)
     * @see #defineElementWithChildBuilder(String, Class, Function, boolean)
     */
    public final <@NonNull ET> InjectedTargetElementBuilder<ET,?> defineElementWithInjectedTargetBuilder(final String injectedElementLocalName, final Class<ET> targetValueClass) {
      return defineElementWithInjectedTargetBuilder(injectedElementLocalName, targetValueClass, targetValueClass, false);
    }

    /**
     * <p>
     * Define a content element which automatically constructs it's target value by
     * {@linkplain XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map) injecting} it's parsed data into
     * the specified <code>targetValueClass</code>.
     * </p>
     * 
     * <p>
     * This method is for defining injected elements which have children or require attribute type mapping before
     * injection. If this is not the case for the element being defined, you should be using the other method to
     * {@linkplain #defineElementWithInjectedTarget(String, Class, Class) define an injected element without children}.
     * If you want to construct the target value yourself, without using injection, you should be using the method to
     * {@linkplain #defineElementWithChildBuilder(String, Class, Function, boolean) build an element definition
     * containing child elements}.
     * </p>
     * 
     * @param <ET> The type of target value which will be provided when the defined element is parsed.
     * @param targetValueClass The {@link Class} object for the type of target value which will be constructed when the
     * defined element is parsed. The {@linkplain Class#getSimpleName() simple name} from this class will also be used
     * as the {@linkplain QName#getLocalPart() local name} of the element being defined (the {@linkplain #getNamespace()
     * current namespace} will be used).
     * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder} which
     * you can use to reference other existing element definitions this one will have as children and specify how those
     * should be injected into the <code>targetValueClass</code>.
     * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
     * @see #defineElementWithInjectedTargetBuilder(String, Class, Class, boolean)
     * @see #defineElementWithInjectedTargetBuilder(String, Class)
     * @see #defineElementWithInjectedTarget(String, Class, Class)
     * @see #defineElementWithChildBuilder(String, Class, Function, boolean)
     */
    public final <@NonNull ET> InjectedTargetElementBuilder<ET,?> defineElementWithInjectedTargetBuilder(final Class<ET> targetValueClass) {
      return defineElementWithInjectedTargetBuilder(targetValueClass.getSimpleName(), targetValueClass, targetValueClass, false);
    }

    /**
     * Define a "wrapper" element, which contains a single child element, and uses that child's target value as it's
     * own.
     * 
     * @param <ET> The type of target value which will be provided when the defined element is parsed.
     * @param wrapperElementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined (the
     * {@linkplain #getNamespace() current namespace} will be used).
     * @param wrappedElementName The name of an existing element which will be the child element wrapped by this one.
     * @param targetValueClass The {@link Class} object for the type of target value which will be constructed when the
     * defined element is parsed.
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
     */
    public final <@NonNull ET> SB defineWrapperElement(final String wrapperElementLocalName, final QName wrappedElementName, final Class<ET> targetValueClass) throws NoSuchElementException {
      return addParser(new WrapperElementParser<ET>(qn(wrapperElementLocalName), getParser(wrappedElementName, targetValueClass)));
    }

    /**
     * Define a "wrapper" element, which contains a single child element, and uses that child's target value as it's
     * own.
     * 
     * @param wrapperElementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined (the
     * {@linkplain #getNamespace() current namespace} will be used).
     * @param wrappedElementName The name of an existing element which will be the child element wrapped by this one.
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
     */
    public final SB defineWrapperElement(final String wrapperElementLocalName, final QName wrappedElementName) throws NoSuchElementException {
      return addParser(new WrapperElementParser<>(qn(wrapperElementLocalName), getParser(wrappedElementName)));
    }

    /**
     * Define a "wrapper" element, which contains a single child element, and uses that child's target value as it's
     * own.
     * 
     * @param wrapperElementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined (the
     * {@linkplain #getNamespace() current namespace} will be used).
     * @param wrappedElementName The name of an existing element which will be the child element wrapped by this one.
     * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
     */
    public final SB defineWrapperElement(final String wrapperElementLocalName, final String wrappedElementName) throws NoSuchElementException {
      return defineWrapperElement(wrapperElementLocalName, qn(wrappedElementName));
    }

    /**
     * Define a "container" element, which don't calculate target values, and whose sole purpose is hosting other
     * content elements found lower in the tree.
     * 
     * @param containerElementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined
     * (the {@linkplain #getNamespace() current namespace} will be used).
     * @return A {@link XMLStreamParser.SchemaBuilder.ChildElementListBuilder ChildElementListBuilder} which you can use
     * to define which elements this definition will have as children.
     */
    public final ChildElementListBuilder<@NonNull ?> defineContainerElementWithChildBuilder(final String containerElementLocalName) {
      return new ChildElementListBuilder<ElementParser<?>>(ElementParser.WILDCARD_CLASS, (childParsers) -> addParser(new ContainerElementParser(qn(containerElementLocalName), childParsers)));
    }

    /**
     * Create an {@link XMLStreamParser} using element definitions from this schema.
     * 
     * @param <T> The type of target values to be streamed by the created parser.
     * @param targetValueClass The {@link Class} object for the type of
     * {@linkplain XMLStreamParser#getTargetValueClass() target value} which will be streamed by the created parser.
     * @param documentElementName The name of the root document element which will be consumed by the created parser.
     * @param targetElementName The name of the primary content element whose target values will be streamed by the
     * created parser.
     * @return The newly created {@link XMLStreamParser} instance.
     * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
     * @see #createParser(Class, String, String)
     */
    public <@NonNull T> XMLStreamParser<T> createParser(final Class<T> targetValueClass, final QName documentElementName, final QName targetElementName) throws NoSuchElementException {
      return new XMLStreamParser<T>(targetValueClass, getParser(documentElementName), getParser(targetElementName, targetValueClass));
    }

    /**
     * Create an {@link XMLStreamParser} using element definitions from this schema.
     * 
     * @param <T> The type of target values to be streamed by the created parser.
     * @param targetValueClass The {@link Class} object for the type of
     * {@linkplain XMLStreamParser#getTargetValueClass() target value} which will be streamed by the created parser.
     * @param documentElementLocalName The {@linkplain QName#getLocalPart() local name} of the root document element
     * which will be consumed by the created parser (the {@linkplain #getNamespace() current namespace} will be used).
     * @param targetElementLocalName The {@linkplain QName#getLocalPart() local name} of the primary content element
     * whose target values will be streamed by the created parser (the {@linkplain #getNamespace() current namespace}
     * will be used).
     * @return The newly created {@link XMLStreamParser} instance.
     * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
     * @see #createParser(Class, QName, QName)
     */
    public <@NonNull T> XMLStreamParser<T> createParser(final Class<T> targetValueClass, final String documentElementLocalName, final String targetElementLocalName) throws NoSuchElementException {
      return createParser(targetValueClass, qn(documentElementLocalName), qn(targetElementLocalName));
    }

    /**
     * This class is used during the definition of a parent element in order to construct a list of element definitions
     * which should become it's children.
     *
     * @param <PT> The type of elements being collected by this builder.
     */
    public final class ChildElementListBuilder<@NonNull PT extends ElementParser<?>> {
      protected final Class<PT> parserType;
      protected final Consumer<PT[]> consumer;
      protected final Set<PT> childParsers = new CopyOnWriteArraySet<>();

      /**
       * Construct a new <code>ChildElementListBuilder</code>.
       * 
       * @param parserType The type of elements being collected by this builder.
       * @param consumer The {@link Consumer} of the resulting list of child elements.
       */
      public ChildElementListBuilder(final Class<PT> parserType, final Consumer<PT[]> consumer) {
        this.parserType = Objects.requireNonNull(parserType);
        this.consumer = Objects.requireNonNull(consumer);
        return;
      }

      /**
       * <p>
       * Add a referenced element definition as a child of the parent element currently being defined.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param <CT> The type of target value which will be constructed when the child element is parsed.
       * @param elementName The name of the referenced element you wish to add as a child.
       * @param targetValueClass The target value type produced by the referenced element definition you wish to add as
       * a child.
       * @return The {@link XMLStreamParser.SchemaBuilder.ChildElementListBuilder ChildElementListBuilder} this method
       * was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #addChild(String, Class)
       * @see #addChild(QName)
       * @see #addChild(String)
       */
      public <@NonNull CT> ChildElementListBuilder<PT> addChild(final QName elementName, final Class<CT> targetValueClass) throws NoSuchElementException {
        childParsers.add(getParser(parserType, elementName, targetValueClass));
        return this;
      }

      /**
       * <p>
       * Add a referenced element definition as a child of the parent element currently being defined.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param <CT> The type of target value which will be constructed when the child element is parsed.
       * @param elementName The {@linkplain QName#getLocalPart() local name} of the referenced element you wish to add
       * as a child (the {@linkplain XMLStreamParser.SchemaBuilder#getNamespace() current namespace} will be used).
       * @param targetValueClass The target value type produced by the referenced element definition you wish to add as
       * a child.
       * @return The {@link XMLStreamParser.SchemaBuilder.ChildElementListBuilder ChildElementListBuilder} this method
       * was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #addChild(QName, Class)
       * @see #addChild(QName)
       * @see #addChild(String)
       */
      public <@NonNull CT> ChildElementListBuilder<PT> addChild(final String elementName, final Class<CT> targetValueClass) throws NoSuchElementException {
        return addChild(qn(elementName), targetValueClass);
      }

      /**
       * <p>
       * Add a referenced element definition as a child of the parent element currently being defined.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param elementName The name of the referenced element you wish to add as a child.
       * @return The {@link XMLStreamParser.SchemaBuilder.ChildElementListBuilder ChildElementListBuilder} this method
       * was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #addChild(QName, Class)
       * @see #addChild(String, Class)
       * @see #addChild(String)
       */
      public ChildElementListBuilder<PT> addChild(final QName elementName) throws NoSuchElementException {
        childParsers.add(getParser(parserType, elementName));
        return this;
      }

      /**
       * <p>
       * Add a referenced element definition as a child of the parent element currently being defined.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param elementName The {@linkplain QName#getLocalPart() local name} of the referenced element you wish to add
       * as a child (the {@linkplain XMLStreamParser.SchemaBuilder#getNamespace() current namespace} will be used).
       * @return The {@link XMLStreamParser.SchemaBuilder.ChildElementListBuilder ChildElementListBuilder} this method
       * was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #addChild(QName, Class)
       * @see #addChild(String, Class)
       * @see #addChild(QName)
       */
      public ChildElementListBuilder<PT> addChild(final String elementName) throws NoSuchElementException {
        return addChild(qn(elementName));
      }

      /**
       * Compile the list of child element definitions which have been provided to this builder and use them to complete
       * the definition of the parent element.
       * 
       * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} being used to define the parent element.
       */
      @SuppressWarnings("unchecked")
      public SB completeDefinition() {
        consumer.accept(childParsers.stream().toArray((n) -> (PT[])java.lang.reflect.Array.newInstance(parserType, n)));
        return schemaBuilderType.cast(SchemaBuilder.this);
      }

    } // ChildElementListBuilder

    /**
     * This class is used during the
     * {@linkplain XMLStreamParser.SchemaBuilder#defineElementWithInjectedTargetBuilder(String, Class, Class, boolean)
     * definition of an injected element} in order to construct a list of element definitions which should become it's
     * children and to create <code>injectionSpecs</code> customizing how the
     * {@linkplain XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map) injection} should be performed.
     *
     * @param <ET> The type of target value which will be constructed when the injected element is parsed.
     * @param <PT> The type of elements being collected by this builder.
     */
    public final class InjectedTargetElementBuilder<@NonNull ET,@NonNull PT extends ElementParser<?>> {
      protected final Class<PT> parserType;
      protected final BiConsumer<Collection<PT>,Map<String,Function<ElementParsingContext<ET>,@Nullable Object>>> consumer;
      protected final Set<PT> childParsers = new CopyOnWriteArraySet<>();
      protected final Map<String,Function<ElementParsingContext<ET>,@Nullable Object>> injectionSpecs = new ConcurrentHashMap<>();

      /**
       * Construct a new <code>InjectedTargetElementBuilder</code>.
       * 
       * @param parserType The type of elements being collected by this builder.
       * @param consumer The {@link Consumer} of the resulting list of child elements and injection specifications.
       */
      public InjectedTargetElementBuilder(final Class<PT> parserType, BiConsumer<Collection<PT>,Map<String,Function<ElementParsingContext<ET>,@Nullable Object>>> consumer) {
        this.parserType = Objects.requireNonNull(parserType);
        this.consumer = Objects.requireNonNull(consumer);
        return;
      }

      /**
       * Add a custom injection specification for <code>injectedFieldName</code>.
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the resulting value
       * should be populated into for injection.
       * @param injectionSpec A Function which will return a value to be injected into <code>injectedFieldName</code>
       * based on the {@link XMLStreamParser.ElementParsingContext ElementParsingContext}.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       */
      public InjectedTargetElementBuilder<ET,PT> injectField(final String injectedFieldName, final Function<ElementParsingContext<ET>,@Nullable Object> injectionSpec) {
        injectionSpecs.put(injectedFieldName, injectionSpec);
        return this;
      }

      /**
       * Specify that any value for <code>attrName</code> on the element currently being defined should be injected into
       * <code>injectedFieldName</code> on the target class after having the <code>attrValueFunction</code> applied.
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the attribute value
       * should be populated into for injection.
       * @param attrName The name of the {@linkplain StartElement#getAttributeByName(QName) attribute value} being
       * injected.
       * @param attrValueFunction A {@link Function} which should be applied to the attribute value before it is
       * injected.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @see #injectAttr(String, String, Function)
       * @see #injectAttr(QName, Function)
       * @see #injectAttr(String, Function)
       * @see #injectAttr(String, QName)
       * @see #injectAttr(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectAttr(final String injectedFieldName, final QName attrName, final Function<? super String,?> attrValueFunction) {
        injectionSpecs.put(injectedFieldName, (ctx) -> {
          final @Nullable String attrValue = ctx.getAttrOrNull(attrName);
          return (attrValue != null) ? attrValueFunction.apply(attrValue) : null;
        });
        return this;
      }

      /**
       * Specify that any value for <code>attrName</code> on the element currently being defined should be injected into
       * <code>injectedFieldName</code> on the target class after having the <code>attrValueFunction</code> applied.
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the attribute value
       * should be populated into for injection.
       * @param attrName The {@linkplain QName#getLocalPart() local name} of the
       * {@linkplain StartElement#getAttributeByName(QName) attribute value} being injected
       * ({@linkplain XMLConstants#NULL_NS_URI no namespace} will be used).
       * @param attrValueFunction A {@link Function} which should be applied to the attribute value before it is
       * injected.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @see #injectAttr(String, QName, Function)
       * @see #injectAttr(QName, Function)
       * @see #injectAttr(String, Function)
       * @see #injectAttr(String, QName)
       * @see #injectAttr(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectAttr(final String injectedFieldName, final String attrName, final Function<? super String,?> attrValueFunction) {
        return injectAttr(injectedFieldName, new QName(XMLConstants.NULL_NS_URI, attrName), attrValueFunction);
      }

      /**
       * Specify that any value for <code>attrName</code> on the element currently being defined should be injected into
       * the target class after having the <code>attrValueFunction</code> applied.
       * 
       * @param attrName The name of the {@linkplain StartElement#getAttributeByName(QName) attribute value} being
       * injected.
       * @param attrValueFunction A {@link Function} which should be applied to the attribute value before it is
       * injected.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @see #injectAttr(String, QName, Function)
       * @see #injectAttr(String, String, Function)
       * @see #injectAttr(String, Function)
       * @see #injectAttr(String, QName)
       * @see #injectAttr(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectAttr(final QName attrName, final Function<? super String,?> attrValueFunction) {
        return injectAttr(attrName.getLocalPart(), attrName, attrValueFunction);
      }

      /**
       * Specify that any value for <code>attrName</code> on the element currently being defined should be injected into
       * the target class after having the <code>attrValueFunction</code> applied.
       * 
       * @param attrName The {@linkplain QName#getLocalPart() local name} of the
       * {@linkplain StartElement#getAttributeByName(QName) attribute value} being injected
       * ({@linkplain XMLConstants#NULL_NS_URI no namespace} will be used).
       * @param attrValueFunction A {@link Function} which should be applied to the attribute value before it is
       * injected.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @see #injectAttr(String, QName, Function)
       * @see #injectAttr(String, String, Function)
       * @see #injectAttr(QName, Function)
       * @see #injectAttr(String, QName)
       * @see #injectAttr(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectAttr(final String attrName, final Function<? super String,?> attrValueFunction) {
        return injectAttr(attrName, new QName(XMLConstants.NULL_NS_URI, attrName), attrValueFunction);
      }

      /**
       * Specify that any value for <code>attrName</code> on the element currently being defined should be injected into
       * <code>injectedFieldName</code> on the target class.
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the attribute value
       * should be populated into for injection.
       * @param attrName The name of the {@linkplain StartElement#getAttributeByName(QName) attribute value} being
       * injected.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @see #injectAttr(String, QName, Function)
       * @see #injectAttr(String, String, Function)
       * @see #injectAttr(QName, Function)
       * @see #injectAttr(String, Function)
       * @see #injectAttr(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectAttr(final String injectedFieldName, final QName attrName) {
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getAttrOrNull(attrName));
        return this;
      }

      /**
       * Specify that any value for <code>attrName</code> on the element currently being defined should be injected into
       * <code>injectedFieldName</code> on the target class.
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the attribute value
       * should be populated into for injection.
       * @param attrName The {@linkplain QName#getLocalPart() local name} of the
       * {@linkplain StartElement#getAttributeByName(QName) attribute value} being injected
       * ({@linkplain XMLConstants#NULL_NS_URI no namespace} will be used).
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @see #injectAttr(String, QName, Function)
       * @see #injectAttr(String, String, Function)
       * @see #injectAttr(QName, Function)
       * @see #injectAttr(String, Function)
       * @see #injectAttr(String, QName)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectAttr(final String injectedFieldName, final String attrName) {
        return injectAttr(injectedFieldName, new QName(XMLConstants.NULL_NS_URI, attrName));
      }

      /**
       * <p>
       * Add the element definition referenced by <code>childElementName</code> as a child of the parent element
       * currently being defined, and specify that it's value should be injected into <code>injectedFieldName</code> on
       * the target class.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param <CT> The type of target value which will be constructed when the child element is parsed.
       * @param <IT> The type of value which will be injected into the field.
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the child value should be
       * populated into for injection.
       * @param childElementName The name of the referenced element you wish to add as a child and have injected.
       * @param childElementTargetValueClass The target value type produced by the referenced element definition you
       * wish to add as a child and have injected.
       * @param injectedValueClass The {@link Class} of value which will be injected into the field.
       * @param injectedValueFunction A {@link Function} to convert the child target value into the injected value.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectChildObject(String, QName)
       * @see #injectChildObject(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public <@NonNull CT,@NonNull IT> InjectedTargetElementBuilder<ET,PT> injectChildObject(final String injectedFieldName, final QName childElementName, final Class<CT> childElementTargetValueClass, final Class<IT> injectedValueClass, final Function<? super CT,? extends IT> injectedValueFunction) throws NoSuchElementException {
        childParsers.add(getParser(parserType, childElementName, childElementTargetValueClass));
        injectionSpecs.put(injectedFieldName, (ctx) -> {
          final @Nullable CT childValue = ctx.getChildValueOrNull(childElementName, childElementTargetValueClass);
          return (childValue != null) ? injectedValueFunction.apply(childValue) : null;
        });
        return this;
      }

      /**
       * <p>
       * Add the element definition referenced by <code>childElementName</code> as a child of the parent element
       * currently being defined, and specify that it's value should be injected into <code>injectedFieldName</code> on
       * the target class.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the child value should be
       * populated into for injection.
       * @param childElementName The name of the referenced element you wish to add as a child and have injected.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectChildObject(String, QName, Class, Class, Function)
       * @see #injectChildObject(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectChildObject(final String injectedFieldName, final QName childElementName) throws NoSuchElementException {
        final PT childElementParser = getParser(parserType, childElementName);
        childParsers.add(childElementParser);
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getChildValueOrNull(childElementName, childElementParser.getTargetValueClass()));
        return this;
      }

      /**
       * <p>
       * Add the element definition referenced by <code>childElementName</code> as a child of the parent element
       * currently being defined, and specify that it's value should be injected into <code>injectedFieldName</code> on
       * the target class.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the child value should be
       * populated into for injection.
       * @param childElementName The {@linkplain QName#getLocalPart() local name} of the referenced element you wish to
       * add as a child and have injected (the {@linkplain XMLStreamParser.SchemaBuilder#getNamespace() current
       * namespace} will be used).
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectChildObject(String, QName, Class, Class, Function)
       * @see #injectChildObject(String, QName)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectChildObject(final String injectedFieldName, final String childElementName) throws NoSuchElementException {
        return injectChildObject(injectedFieldName, qn(childElementName));
      }

      /**
       * <p>
       * Add the element definition referenced by <code>childElementName</code> as a child of the parent element
       * currently being defined, and specify that it's values should be injected as an {@link java.lang.reflect.Array
       * Array} into <code>injectedFieldName</code> on the target class.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param <CT> The type of target value which will be constructed when the child element is parsed.
       * @param <IT> The type of value which will be injected into the field.
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the child values should
       * be populated into for injection.
       * @param childElementName The name of the referenced element you wish to add as a child and have injected.
       * @param childElementTargetValueClass The target value type produced by the referenced element definition you
       * wish to add as a child and have injected.
       * @param injectedValueClass The {@link Class} of value which will be injected into the field.
       * @param injectedValueFunction A {@link Function} to convert the child target value into the injected value.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectChildArray(String, QName)
       * @see #injectChildArray(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public <@NonNull CT,@NonNull IT> InjectedTargetElementBuilder<ET,PT> injectChildArray(final String injectedFieldName, final QName childElementName, final Class<CT> childElementTargetValueClass, final Class<IT> injectedValueClass, final Function<? super CT,? extends IT> injectedValueFunction) throws NoSuchElementException {
        childParsers.add(getParser(parserType, childElementName, childElementTargetValueClass));
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getChildValues(childElementName, childElementTargetValueClass).map(injectedValueFunction).toArray((n) -> (Object[])java.lang.reflect.Array.newInstance(injectedValueClass, n)));
        return this;
      }

      /**
       * <p>
       * Add the element definition referenced by <code>childElementName</code> as a child of the parent element
       * currently being defined, and specify that it's values should be injected as an {@link java.lang.reflect.Array
       * Array} into <code>injectedFieldName</code> on the target class.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the child values should
       * be populated into for injection.
       * @param childElementName The name of the referenced element you wish to add as a child and have injected.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectChildArray(String, QName, Class, Class, Function)
       * @see #injectChildArray(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectChildArray(final String injectedFieldName, final QName childElementName) throws NoSuchElementException {
        final PT childElementParser = getParser(parserType, childElementName);
        childParsers.add(childElementParser);
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getChildValues(childElementName, childElementParser.getTargetValueClass()).toArray((n) -> (Object[])java.lang.reflect.Array.newInstance(childElementParser.getTargetValueClass(), n)));
        return this;
      }

      /**
       * <p>
       * Add the element definition referenced by <code>childElementName</code> as a child of the parent element
       * currently being defined, and specify that it's values should be injected as an {@link java.lang.reflect.Array
       * Array} into <code>injectedFieldName</code> on the target class.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the child values should
       * be populated into for injection.
       * @param childElementName The {@linkplain QName#getLocalPart() local name} of the referenced element you wish to
       * add as a child and have injected (the {@linkplain XMLStreamParser.SchemaBuilder#getNamespace() current
       * namespace} will be used).
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectChildArray(String, QName, Class, Class, Function)
       * @see #injectChildArray(String, QName)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectChildArray(final String injectedFieldName, final String childElementName) throws NoSuchElementException {
        return injectChildArray(injectedFieldName, qn(childElementName));
      }

      /**
       * <p>
       * Add the element definition referenced by <code>childElementName</code> as a child of the parent element
       * currently being defined, and specify that it's values should be injected as a {@link List} into
       * <code>injectedFieldName</code> on the target class.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param <CT> The type of target value which will be constructed when the child element is parsed.
       * @param <IT> The type of value which will be injected into the field.
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the child values should
       * be populated into for injection.
       * @param childElementName The name of the referenced element you wish to add as a child and have injected.
       * @param childElementTargetValueClass The target value type produced by the referenced element definition you
       * wish to add as a child and have injected.
       * @param injectedValueClass The {@link Class} of value which will be injected into the field.
       * @param injectedValueFunction A {@link Function} to convert the child target value into the injected value.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectChildList(String, QName)
       * @see #injectChildList(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public <@NonNull CT,@NonNull IT> InjectedTargetElementBuilder<ET,PT> injectChildList(final String injectedFieldName, final QName childElementName, final Class<CT> childElementTargetValueClass, final Class<IT> injectedValueClass, final Function<? super CT,? extends IT> injectedValueFunction) throws NoSuchElementException {
        childParsers.add(getParser(parserType, childElementName, childElementTargetValueClass));
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getChildValues(childElementName, childElementTargetValueClass).map(injectedValueFunction).collect(Collectors.toList()));
        return this;
      }

      /**
       * <p>
       * Add the element definition referenced by <code>childElementName</code> as a child of the parent element
       * currently being defined, and specify that it's values should be injected as a {@link List} into
       * <code>injectedFieldName</code> on the target class.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the child values should
       * be populated into for injection.
       * @param childElementName The name of the referenced element you wish to add as a child and have injected.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectChildList(String, QName, Class, Class, Function)
       * @see #injectChildList(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectChildList(final String injectedFieldName, final QName childElementName) throws NoSuchElementException {
        final PT childElementParser = getParser(parserType, childElementName);
        childParsers.add(childElementParser);
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getChildValues(childElementName, childElementParser.getTargetValueClass()).collect(Collectors.toList()));
        return this;
      }

      /**
       * <p>
       * Add the element definition referenced by <code>childElementName</code> as a child of the parent element
       * currently being defined, and specify that it's values should be injected as a {@link List} into
       * <code>injectedFieldName</code> on the target class.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the child values should
       * be populated into for injection.
       * @param childElementName The {@linkplain QName#getLocalPart() local name} of the referenced element you wish to
       * add as a child and have injected (the {@linkplain XMLStreamParser.SchemaBuilder#getNamespace() current
       * namespace} will be used).
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectChildList(String, QName, Class, Class, Function)
       * @see #injectChildList(String, QName)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectChildList(final String injectedFieldName, final String childElementName) throws NoSuchElementException {
        return injectChildList(injectedFieldName, qn(childElementName));
      }

      /**
       * <p>
       * Add the element definition referenced by <code>childElementName</code> as a child of the parent element
       * currently being defined, and specify that it's values should be injected as a {@link Set} into
       * <code>injectedFieldName</code> on the target class.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param <CT> The type of target value which will be constructed when the child element is parsed.
       * @param <IT> The type of value which will be injected into the field.
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the child values should
       * be populated into for injection.
       * @param childElementName The name of the referenced element you wish to add as a child and have injected.
       * @param childElementTargetValueClass The target value type produced by the referenced element definition you
       * wish to add as a child and have injected.
       * @param injectedValueClass The {@link Class} of value which will be injected into the field.
       * @param injectedValueFunction A {@link Function} to convert the child target value into the injected value.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectChildSet(String, QName)
       * @see #injectChildSet(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public <@NonNull CT,@NonNull IT> InjectedTargetElementBuilder<ET,PT> injectChildSet(final String injectedFieldName, final QName childElementName, final Class<CT> childElementTargetValueClass, final Class<IT> injectedValueClass, final Function<? super CT,? extends IT> injectedValueFunction) throws NoSuchElementException {
        childParsers.add(getParser(parserType, childElementName, childElementTargetValueClass));
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getChildValues(childElementName, childElementTargetValueClass).map(injectedValueFunction).collect(Collectors.toSet()));
        return this;
      }

      /**
       * <p>
       * Add the element definition referenced by <code>childElementName</code> as a child of the parent element
       * currently being defined, and specify that it's values should be injected as a {@link Set} into
       * <code>injectedFieldName</code> on the target class.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the child values should
       * be populated into for injection.
       * @param childElementName The name of the referenced element you wish to add as a child and have injected.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectChildSet(String, QName, Class, Class, Function)
       * @see #injectChildSet(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectChildSet(final String injectedFieldName, final QName childElementName) throws NoSuchElementException {
        final PT childElementParser = getParser(parserType, childElementName);
        childParsers.add(childElementParser);
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getChildValues(childElementName, childElementParser.getTargetValueClass()).collect(Collectors.toSet()));
        return this;
      }

      /**
       * <p>
       * Add the element definition referenced by <code>childElementName</code> as a child of the parent element
       * currently being defined, and specify that it's values should be injected as a {@link Set} into
       * <code>injectedFieldName</code> on the target class.
       * </p>
       * 
       * <p>
       * Note that the child element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the child values should
       * be populated into for injection.
       * @param childElementName The {@linkplain QName#getLocalPart() local name} of the referenced element you wish to
       * add as a child and have injected (the {@linkplain XMLStreamParser.SchemaBuilder#getNamespace() current
       * namespace} will be used).
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectChildSet(String, QName, Class, Class, Function)
       * @see #injectChildSet(String, QName)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectChildSet(final String injectedFieldName, final String childElementName) throws NoSuchElementException {
        return injectChildSet(injectedFieldName, qn(childElementName));
      }

      /**
       * <p>
       * Specify that any {@linkplain XMLStreamParser.ElementParsingContext#getSavedValueOrNull(QName, Class) saved
       * value} for the element definition referenced by <code>savedElementName</code> should be injected into
       * <code>injectedFieldName</code> on the target class of the element currently being defined.
       * </p>
       * 
       * <p>
       * Note that the saved element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param <ST> The type of target value which will be constructed when the saved element is parsed.
       * @param <IT> The type of value which will be injected into the field.
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the saved value should be
       * populated into for injection.
       * @param savedElementName The name of the element whose
       * {@linkplain XMLStreamParser.ElementParsingContext#getSavedValueOrNull(QName, Class) saved value} you wish to
       * have injected.
       * @param savedElementTargetValueClass The target value type produced by the element whose saved value you wish to
       * have injected.
       * @param injectedValueClass The {@link Class} of value which will be injected into the field.
       * @param injectedValueFunction A {@link Function} to convert the saved target value into the injected value.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectSavedObject(String, QName)
       * @see #injectSavedObject(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public <@NonNull ST,@NonNull IT> InjectedTargetElementBuilder<ET,PT> injectSavedObject(final String injectedFieldName, final QName savedElementName, final Class<ST> savedElementTargetValueClass, final Class<IT> injectedValueClass, final Function<? super ST,? extends IT> injectedValueFunction) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, (ctx) -> {
          final @Nullable ST savedValue = ctx.getSavedValueOrNull(savedElementName, savedElementTargetValueClass);
          return (savedValue != null) ? injectedValueFunction.apply(savedValue) : null;
        });
        return this;
      }

      /**
       * <p>
       * Specify that any {@linkplain XMLStreamParser.ElementParsingContext#getSavedValueOrNull(QName, Class) saved
       * value} for the element definition referenced by <code>savedElementName</code> should be injected into
       * <code>injectedFieldName</code> on the target class of the element currently being defined.
       * </p>
       * 
       * <p>
       * Note that the saved element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the saved value should be
       * populated into for injection.
       * @param savedElementName The name of the element whose
       * {@linkplain XMLStreamParser.ElementParsingContext#getSavedValueOrNull(QName, Class) saved value} you wish to
       * have injected.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectSavedObject(String, QName, Class, Class, Function)
       * @see #injectSavedObject(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectSavedObject(final String injectedFieldName, final QName savedElementName) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getSavedValueOrNull(savedElementName, getParser(savedElementName).getTargetValueClass()));
        return this;
      }

      /**
       * <p>
       * Specify that any {@linkplain XMLStreamParser.ElementParsingContext#getSavedValueOrNull(QName, Class) saved
       * value} for the element definition referenced by <code>savedElementName</code> should be injected into
       * <code>injectedFieldName</code> on the target class of the element currently being defined.
       * </p>
       * 
       * <p>
       * Note that the saved element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the saved value should be
       * populated into for injection.
       * @param savedElementName The {@linkplain QName#getLocalPart() local name} of the element whose
       * {@linkplain XMLStreamParser.ElementParsingContext#getSavedValueOrNull(QName, Class) saved value} you wish to
       * have injected (the {@linkplain XMLStreamParser.SchemaBuilder#getNamespace() current namespace} will be used).
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectSavedObject(String, QName, Class, Class, Function)
       * @see #injectSavedObject(String, QName)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectSavedObject(final String injectedFieldName, final String savedElementName) throws NoSuchElementException {
        return injectSavedObject(injectedFieldName, qn(savedElementName));
      }

      /**
       * <p>
       * Specify that any {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values}
       * for the element definition referenced by <code>savedElementName</code> should be injected as an
       * {@link java.lang.reflect.Array Array} into <code>injectedFieldName</code> on the target class of the element
       * currently being defined.
       * </p>
       * 
       * <p>
       * Note that the saved element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param <ST> The type of target value which will be constructed when the saved element is parsed.
       * @param <IT> The type of value which will be injected into the field.
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the saved values should
       * be populated into for injection.
       * @param savedElementName The name of the element whose
       * {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values} you wish to have
       * injected.
       * @param savedElementTargetValueClass The target value type produced by the element whose saved values you wish
       * to have injected.
       * @param injectedValueClass The {@link Class} of value which will be injected into the field.
       * @param injectedValueFunction A {@link Function} to convert the saved target value into the injected value.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectSavedArray(String, QName)
       * @see #injectSavedArray(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public <@NonNull ST,@NonNull IT> InjectedTargetElementBuilder<ET,PT> injectSavedArray(final String injectedFieldName, final QName savedElementName, final Class<ST> savedElementTargetValueClass, final Class<IT> injectedValueClass, final Function<? super ST,? extends IT> injectedValueFunction) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getSavedValues(savedElementName, savedElementTargetValueClass).map(injectedValueFunction).toArray((n) -> (Object[])java.lang.reflect.Array.newInstance(injectedValueClass, n)));
        return this;
      }

      /**
       * <p>
       * Specify that any {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values}
       * for the element definition referenced by <code>savedElementName</code> should be injected as an
       * {@link java.lang.reflect.Array Array} into <code>injectedFieldName</code> on the target class of the element
       * currently being defined.
       * </p>
       * 
       * <p>
       * Note that the saved element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the saved values should
       * be populated into for injection.
       * @param savedElementName The name of the element whose
       * {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values} you wish to have
       * injected.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectSavedArray(String, QName, Class, Class, Function)
       * @see #injectSavedArray(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectSavedArray(final String injectedFieldName, final QName savedElementName) throws NoSuchElementException {
        final PT savedElementParser = getParser(parserType, savedElementName);
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getSavedValues(savedElementName, savedElementParser.getTargetValueClass()).toArray((n) -> (Object[])java.lang.reflect.Array.newInstance(savedElementParser.getTargetValueClass(), n)));
        return this;
      }

      /**
       * <p>
       * Specify that any {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values}
       * for the element definition referenced by <code>savedElementName</code> should be injected as an
       * {@link java.lang.reflect.Array Array} into <code>injectedFieldName</code> on the target class of the element
       * currently being defined.
       * </p>
       * 
       * <p>
       * Note that the saved element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the saved values should
       * be populated into for injection.
       * @param savedElementName The {@linkplain QName#getLocalPart() local name} of the element whose
       * {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values} you wish to have
       * injected (the {@linkplain XMLStreamParser.SchemaBuilder#getNamespace() current namespace} will be used).
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectSavedArray(String, QName, Class, Class, Function)
       * @see #injectSavedArray(String, QName)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectSavedArray(final String injectedFieldName, final String savedElementName) throws NoSuchElementException {
        return injectSavedArray(injectedFieldName, qn(savedElementName));
      }

      /**
       * <p>
       * Specify that any {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values}
       * for the element definition referenced by <code>savedElementName</code> should be injected as a {@link List}
       * into <code>injectedFieldName</code> on the target class of the element currently being defined.
       * </p>
       * 
       * <p>
       * Note that the saved element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param <ST> The type of target value which will be constructed when the saved element is parsed.
       * @param <IT> The type of value which will be injected into the field.
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the saved values should
       * be populated into for injection.
       * @param savedElementName The name of the element whose
       * {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values} you wish to have
       * injected.
       * @param savedElementTargetValueClass The target value type produced by the element whose saved values you wish
       * to have injected.
       * @param injectedValueClass The {@link Class} of value which will be injected into the field.
       * @param injectedValueFunction A {@link Function} to convert the saved target value into the injected value.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectSavedList(String, QName)
       * @see #injectSavedList(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public <@NonNull ST,@NonNull IT> InjectedTargetElementBuilder<ET,PT> injectSavedList(final String injectedFieldName, final QName savedElementName, final Class<ST> savedElementTargetValueClass, final Class<IT> injectedValueClass, final Function<? super ST,? extends IT> injectedValueFunction) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getChildValues(savedElementName, savedElementTargetValueClass).map(injectedValueFunction).collect(Collectors.toList()));
        return this;
      }

      /**
       * <p>
       * Specify that any {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values}
       * for the element definition referenced by <code>savedElementName</code> should be injected as a {@link List}
       * into <code>injectedFieldName</code> on the target class of the element currently being defined.
       * </p>
       * 
       * <p>
       * Note that the saved element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the saved values should
       * be populated into for injection.
       * @param savedElementName The name of the element whose
       * {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values} you wish to have
       * injected.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectSavedList(String, QName, Class, Class, Function)
       * @see #injectSavedList(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectSavedList(final String injectedFieldName, final QName savedElementName) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getChildValues(savedElementName, getParser(savedElementName).getTargetValueClass()).collect(Collectors.toList()));
        return this;
      }

      /**
       * <p>
       * Specify that any {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values}
       * for the element definition referenced by <code>savedElementName</code> should be injected as a {@link List}
       * into <code>injectedFieldName</code> on the target class of the element currently being defined.
       * </p>
       * 
       * <p>
       * Note that the saved element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the saved values should
       * be populated into for injection.
       * @param savedElementName The {@linkplain QName#getLocalPart() local name} of the element whose
       * {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values} you wish to have
       * injected (the {@linkplain XMLStreamParser.SchemaBuilder#getNamespace() current namespace} will be used).
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectSavedList(String, QName, Class, Class, Function)
       * @see #injectSavedList(String, QName)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectSavedList(final String injectedFieldName, final String savedElementName) throws NoSuchElementException {
        return injectSavedList(injectedFieldName, qn(savedElementName));
      }

      /**
       * <p>
       * Specify that any {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values}
       * for the element definition referenced by <code>savedElementName</code> should be injected as a {@link Set} into
       * <code>injectedFieldName</code> on the target class of the element currently being defined.
       * </p>
       * 
       * <p>
       * Note that the saved element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param <ST> The type of target value which will be constructed when the saved element is parsed.
       * @param <IT> The type of value which will be injected into the field.
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the saved values should
       * be populated into for injection.
       * @param savedElementName The name of the element whose
       * {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values} you wish to have
       * injected.
       * @param savedElementTargetValueClass The target value type produced by the element whose saved values you wish
       * to have injected.
       * @param injectedValueClass The {@link Class} of value which will be injected into the field.
       * @param injectedValueFunction A {@link Function} to convert the saved target value into the injected value.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectSavedSet(String, QName)
       * @see #injectSavedSet(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public <@NonNull ST,@NonNull IT> InjectedTargetElementBuilder<ET,PT> injectSavedSet(final String injectedFieldName, final QName savedElementName, final Class<ST> savedElementTargetValueClass, final Class<IT> injectedValueClass, final Function<? super ST,? extends IT> injectedValueFunction) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getChildValues(savedElementName, savedElementTargetValueClass).map(injectedValueFunction).collect(Collectors.toSet()));
        return this;
      }

      /**
       * <p>
       * Specify that any {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values}
       * for the element definition referenced by <code>savedElementName</code> should be injected as a {@link Set} into
       * <code>injectedFieldName</code> on the target class of the element currently being defined.
       * </p>
       * 
       * <p>
       * Note that the saved element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the saved values should
       * be populated into for injection.
       * @param savedElementName The name of the element whose
       * {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values} you wish to have
       * injected.
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectSavedSet(String, QName, Class, Class, Function)
       * @see #injectSavedSet(String, String)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectSavedSet(final String injectedFieldName, final QName savedElementName) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, (ctx) -> ctx.getChildValues(savedElementName, getParser(savedElementName).getTargetValueClass()).collect(Collectors.toSet()));
        return this;
      }

      /**
       * <p>
       * Specify that any {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values}
       * for the element definition referenced by <code>savedElementName</code> should be injected as a {@link Set} into
       * <code>injectedFieldName</code> on the target class of the element currently being defined.
       * </p>
       * 
       * <p>
       * Note that the saved element must already have been defined <em>prior</em> to it being referenced here.
       * </p>
       * 
       * @param injectedFieldName The name of the {@linkplain Record#set(Field, Object) field} the saved values should
       * be populated into for injection.
       * @param savedElementName The {@linkplain QName#getLocalPart() local name} of the element whose
       * {@linkplain XMLStreamParser.ElementParsingContext#getSavedValues(QName, Class) saved values} you wish to have
       * injected (the {@linkplain XMLStreamParser.SchemaBuilder#getNamespace() current namespace} will be used).
       * @return The {@link XMLStreamParser.SchemaBuilder.InjectedTargetElementBuilder InjectedTargetElementBuilder}
       * this method was invoked on.
       * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
       * @see #injectSavedSet(String, QName, Class, Class, Function)
       * @see #injectSavedSet(String, QName)
       * @see XMLStreamParser.ElementParsingContext#getInjectedValue(Class, Map)
       */
      public InjectedTargetElementBuilder<ET,PT> injectSavedSet(final String injectedFieldName, final String savedElementName) throws NoSuchElementException {
        return injectSavedSet(injectedFieldName, qn(savedElementName));
      }

      /**
       * Compile the list of child element definitions and injection specifications which have been provided to this
       * builder and use them to complete the definition of the parent element.
       * 
       * @return The {@link XMLStreamParser.SchemaBuilder SchemaBuilder} being used to define the parent element.
       */
      public SB completeDefinition() {
        consumer.accept(childParsers, injectionSpecs);
        return schemaBuilderType.cast(SchemaBuilder.this);
      }

    } // SchemaBuilder.InjectedTargetElementBuilder

  } // SchemaBuilder

}
