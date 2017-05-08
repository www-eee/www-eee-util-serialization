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
 * {@linkplain #parse(InputStream) parse} XML, providing you a {@link Stream} of dynamically constructed target values.
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
   * An interface providing information about the {@linkplain #getStartElement() element} currently being parsed,
   * including it's {@linkplain #getElementName() name}, {@linkplain #getTargetValueClass() type},
   * {@linkplain #getElementContext() context}, {@linkplain #getAttrs() attributes}, and {@linkplain #getChildValues()
   * children}, etc. Generally, when the parser needs to calculate the {@linkplain #getTargetValueClass() target value}
   * for a newly parsed element, an implementation of this interface will be provided to a target value calculation
   * {@link Function}, which is generally supplied when an element is
   * {@linkplain SchemaBuilder#defineElement(String, Class, Function) defined}.
   * </p>
   * 
   * <p>
   * In addition to information about the element currently being parsed, this interface also provides access to a
   * global store of {@linkplain #getSavedValues(QName, Class) saved element values}.
   * </p>
   * 
   * @param <T> The {@linkplain #getTargetValueClass() target value class} of the element being parsed.
   */
  public interface ElementParsingContext<@NonNull T> {

    public Class<T> getTargetValueClass();

    public QName getElementName();

    public default String getNamespaceURI() {
      return getElementName().getNamespaceURI();
    }

    public StartElement getStartElement();

    public Deque<StartElement> getElementContext();

    @SuppressWarnings("unchecked")
    public default Map<String,String> getAttrs() {
      return Collections.unmodifiableMap(StreamSupport.stream(Spliterators.spliteratorUnknownSize((Iterator<Attribute>)getStartElement().getAttributes(), Spliterator.NONNULL | Spliterator.DISTINCT | Spliterator.IMMUTABLE), false).map((attr) -> new AbstractMap.SimpleImmutableEntry<>(attr.getName().getLocalPart(), attr.getValue())).collect(Collectors.<Map.Entry<String,String>,String,String> toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    public default @Nullable String getAttrOrNull(final QName attrName) {
      final @Nullable Attribute attr = getStartElement().getAttributeByName(attrName);
      return (attr != null) ? attr.getValue() : null;
    }

    public default <A> @Nullable A getAttrOrNull(final QName attrName, final Function<? super String,? extends A> targetValueFunction) {
      final @Nullable String attr = getAttrOrNull(attrName);
      return (attr != null) ? targetValueFunction.apply(attr) : null;
    }

    public default @Nullable String getAttrOrNull(final String attrLocalName) {
      return getAttrOrNull(new QName(XMLConstants.NULL_NS_URI, attrLocalName));
    }

    public default <A> @Nullable A getAttrOrNull(final String attrLocalName, final Function<? super String,? extends A> targetValueFunction) {
      final @Nullable String attr = getAttrOrNull(attrLocalName);
      return (attr != null) ? targetValueFunction.apply(attr) : null;
    }

    public default Optional<String> getOptionalAttr(final QName attrName) {
      return Optional.ofNullable(getStartElement().getAttributeByName(attrName)).map(Attribute::getValue);
    }

    public default <A> Optional<A> getOptionalAttr(final QName attrName, final Function<? super String,? extends A> targetValueFunction) {
      return getOptionalAttr(attrName).map(targetValueFunction);
    }

    public default Optional<String> getOptionalAttr(final String attrLocalName) {
      return getOptionalAttr(new QName(XMLConstants.NULL_NS_URI, attrLocalName));
    }

    public default <A> Optional<A> getOptionalAttr(final String attrLocalName, final Function<? super String,? extends A> targetValueFunction) {
      return getOptionalAttr(attrLocalName).map(targetValueFunction);
    }

    public default String getRequiredAttr(final QName attrName) throws NoSuchElementException {
      return getOptionalAttr(attrName).orElseThrow(() -> new NoSuchElementException("Element '" + getElementName().getLocalPart() + "' has no '" + attrName.getLocalPart() + "' attribute"));
    }

    public default <A> A getRequiredAttr(final QName attrName, final Function<? super String,? extends A> targetValueFunction) throws NoSuchElementException {
      return targetValueFunction.apply(getRequiredAttr(attrName));
    }

    public default String getRequiredAttr(final String attrLocalName) throws NoSuchElementException {
      return getRequiredAttr(new QName(XMLConstants.NULL_NS_URI, attrLocalName));
    }

    public default <A> A getRequiredAttr(final String attrLocalName, final Function<? super String,? extends A> targetValueFunction) throws NoSuchElementException {
      return targetValueFunction.apply(getRequiredAttr(attrLocalName));
    }

    /**
     * This method provides access to the target values of any previously parsed elements within the current document
     * which were defined as having their target value saved.
     * 
     * @param <S> The target value type of the desired saved values. 
     * @param savedElementName The name of the element for which saved values are desired. If <code>null</code>,
     * <em>all</em> saved values of the specified <code>targetValueClass</code> will be returned.
     * @param targetValueClass The target value class of the desired saved values.
     * @return A Stream of values.
     */
    public <@NonNull S> Stream<S> getSavedValues(final @Nullable QName savedElementName, final Class<S> targetValueClass);

    public default <@NonNull S> Stream<S> getSavedValues(final @Nullable String savedElementLocalName, final Class<S> targetValueClass) {
      return getSavedValues((savedElementLocalName != null) ? new QName(getNamespaceURI(), savedElementLocalName) : null, targetValueClass);
    }

    public default <@NonNull S> Optional<S> getOptionalSavedValue(final @Nullable QName savedElementName, final Class<S> targetValueClass) throws NoSuchElementException {
      return getSavedValues(savedElementName, targetValueClass).findFirst();
    }

    public default <@NonNull S> Optional<S> getOptionalSavedValue(final @Nullable String savedElementLocalName, final Class<S> targetValueClass) throws NoSuchElementException {
      return getOptionalSavedValue((savedElementLocalName != null) ? new QName(getNamespaceURI(), savedElementLocalName) : null, targetValueClass);
    }

    public default <@NonNull S> @Nullable S getSavedValueOrNull(final @Nullable QName savedElementName, final Class<S> targetValueClass) throws NoSuchElementException {
      final Optional<S> saved = getOptionalSavedValue(savedElementName, targetValueClass);
      return saved.isPresent() ? saved.get() : null;
    }

    public default <@NonNull S> @Nullable S getSavedValueOrNull(final @Nullable String savedElementLocalName, final Class<S> targetValueClass) throws NoSuchElementException {
      final Optional<S> saved = getOptionalSavedValue(savedElementLocalName, targetValueClass);
      return saved.isPresent() ? saved.get() : null;
    }

    public default <@NonNull S> S getRequiredSavedValue(final @Nullable QName savedElementName, final Class<S> targetValueClass) throws NoSuchElementException {
      return getOptionalSavedValue(savedElementName, targetValueClass).orElseThrow(() -> new NoSuchElementException("No " + ((savedElementName != null) ? "'" + savedElementName.getLocalPart() + "' " : "") + " saved element with '" + targetValueClass.getSimpleName() + "' target value class found"));
    }

    public default <@NonNull S> S getRequiredSavedValue(final @Nullable String savedElementLocalName, final Class<S> targetValueClass) throws NoSuchElementException {
      return getRequiredSavedValue((savedElementLocalName != null) ? new QName(getNamespaceURI(), savedElementLocalName) : null, targetValueClass);
    }

    public Stream<Map.Entry<Map.Entry<QName,Class<?>>,List<?>>> getChildValues();

    public default <@NonNull ET> Stream<ET> getChildValues(final @Nullable QName childElementName, final Class<ET> targetValueClass) {
      return getChildValues().filter((entry) -> (childElementName == null) || (childElementName.equals(entry.getKey().getKey()))).filter((entry) -> targetValueClass.isAssignableFrom(entry.getKey().getValue())).<List<?>> map(Map.Entry::getValue).flatMap(List::stream).map((value) -> targetValueClass.cast(value));
    }

    public default <@NonNull ET> Stream<ET> getChildValues(final @Nullable String childElementLocalName, final Class<ET> targetValueClass) {
      return getChildValues((childElementLocalName != null) ? new QName(getNamespaceURI(), childElementLocalName) : null, targetValueClass);
    }

    public default <@NonNull ET> Optional<ET> getOptionalChildValue(final @Nullable QName childElementName, final Class<ET> targetValueClass) {
      return getChildValues(childElementName, targetValueClass).findFirst();
    }

    public default <@NonNull ET> Optional<ET> getOptionalChildValue(final @Nullable String childElementLocalName, final Class<ET> targetValueClass) {
      return getOptionalChildValue((childElementLocalName != null) ? new QName(getNamespaceURI(), childElementLocalName) : null, targetValueClass);
    }

    public default <@NonNull ET> @Nullable ET getChildValueOrNull(final @Nullable QName childElementName, final Class<ET> targetValueClass) {
      final Optional<ET> child = getOptionalChildValue(childElementName, targetValueClass);
      return child.isPresent() ? child.get() : null;
    }

    public default <@NonNull ET> @Nullable ET getChildValueOrNull(final @Nullable String childElementLocalName, final Class<ET> targetValueClass) {
      final Optional<ET> child = getOptionalChildValue(childElementLocalName, targetValueClass);
      return child.isPresent() ? child.get() : null;
    }

    public default <@NonNull ET> ET getRequiredChildValue(final @Nullable QName childElementName, final Class<ET> targetValueClass) throws NoSuchElementException {
      return getOptionalChildValue(childElementName, targetValueClass).orElseThrow(() -> new NoSuchElementException("No " + ((childElementName != null) ? "'" + childElementName.getLocalPart() + "' " : "") + " child element with '" + targetValueClass.getSimpleName() + "' target class found"));
    }

    public default <@NonNull ET> ET getRequiredChildValue(final @Nullable String childElementLocalName, final Class<ET> targetValueClass) throws NoSuchElementException {
      return getRequiredChildValue((childElementLocalName != null) ? new QName(getNamespaceURI(), childElementLocalName) : null, targetValueClass);
    }

  } // ElementParsingContext

  private final class TargetValueSpliterator implements Spliterator<T> {
    private final int CHARACTERISTICS = 0 | Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.NONNULL | Spliterator.IMMUTABLE;
    private final ElementParser<?>.ParsingContextImpl parentContext;
    private final XMLEventReader reader;

    public TargetValueSpliterator(final ElementParser<?>.ParsingContextImpl parentContext, final XMLEventReader reader) throws IllegalArgumentException {
      if (!((ElementParser<?>)parentContext.getParser()).getChildParsers().stream().anyMatch(targetParser::equals)) throw new IllegalStateException("Current parser not parent of target parser");
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

          final Optional<? extends ContentParser<?,?>> parser = ((ElementParser<?>)parentContext.getParser()).findChildParserFor(event);
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
   * A {@link ParsingException} which indicates an {@link XMLStreamException} was encountered during
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

  public abstract static class ContextualParsingException extends ParsingException {
    protected final ElementParser<?>.ParsingContextImpl context;

    protected ContextualParsingException(final ElementParser<?>.ParsingContextImpl context) {
      super();
      this.context = context;
      return;
    }

    protected ContextualParsingException(final Throwable cause, final ElementParser<?>.ParsingContextImpl context) {
      super(cause);
      this.context = context;
      return;
    }

    protected ContextualParsingException(final String message, final Throwable cause, final ElementParser<?>.ParsingContextImpl context) {
      super(message, cause);
      this.context = context;
      return;
    }

    public ElementParsingContext<?> getElementParsingContext() {
      return context;
    }

  } // ContextualParsingException

  /**
   * A {@link ParsingException} which indicates a problem occurred while mapping the
   * {@linkplain XMLStreamParser#parse(InputStream) parsed} XML into a {@linkplain XMLStreamParser#getTargetValueClass()
   * target value}.
   */
  public static class ElementValueParsingException extends ContextualParsingException {

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
  private static class TerminatingParserException extends ContextualParsingException {

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

    public ContainerElementParser(final QName elementName, final @NonNull ElementParser<?> @Nullable... childParsers) {
      super(StartElement.class, elementName, (context) -> context.getStartElement(), false, childParsers);
      return;
    }

  } // ContainerElementParser

  protected static class WrapperElementParser<@NonNull T> extends ElementParser<T> {

    public WrapperElementParser(final QName elementName, final ElementParser<T> wrappedElement) {
      super(wrappedElement.targetValueClass, elementName, (ctx) -> cast(ctx).getRequiredChildValue(wrappedElement), false, wrappedElement);
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
    protected static final DSLContext DSL_CONTEXT = DSL.using(SQLDialect.DEFAULT);

    public InjectedTargetElementParser(final Class<T> targetValueClass, final QName elementName, final boolean saveTargetValue, final @Nullable Collection<? extends ElementParser<?>> childParsers, final @Nullable Map<String,? extends InjectionSpec<T,?>> injectionSpecs) throws IllegalArgumentException {
      super(targetValueClass, elementName, (ctx) -> inject(targetValueClass, ctx, injectionSpecs), saveTargetValue, childParsers);
      return;
    }

    protected static final <@NonNull T> T inject(final Class<? extends T> targetValueClass, final ElementParsingContext<T> ctx, final @Nullable Map<String,? extends InjectionSpec<T,?>> injectionSpecs) throws ElementValueParsingException {
      final Map<String,Field<Object>> fields = ((injectionSpecs != null) ? Stream.concat(ctx.getAttrs().keySet().stream(), injectionSpecs.keySet().stream()) : ctx.getAttrs().keySet().stream()).distinct().map((injectedFieldName) -> new AbstractMap.SimpleImmutableEntry<>(injectedFieldName, DSL.field(DSL.name(injectedFieldName)))).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
      final Record record = DSL_CONTEXT.newRecord(fields.values().stream().toArray(Field<?>[]::new));
      ctx.getAttrs().entrySet().stream().forEach((attr) -> record.<Object> set(Objects.requireNonNull(fields.get(attr.getKey())), attr.getValue()));
      if (injectionSpecs != null) injectionSpecs.entrySet().stream().forEach((spec) -> record.<Object> set(Objects.requireNonNull(fields.get(spec.getKey())), spec.getValue().apply(ctx)));
      try {
        return record.into(targetValueClass);
      } catch (MappingException me) {
        throw new ElementValueParsingException(me, cast(ctx));
      }
    }

    protected static abstract class InjectionSpec<@NonNull ET,@NonNull ST> implements Function<ElementParsingContext<ET>,@Nullable Object> {
      protected final Class<ST> sourceTargetValueClass;
      protected final @Nullable QName sourceName;
      protected final boolean fromSavedValues;

      protected InjectionSpec(final Class<ST> sourceTargetValueClass, final @Nullable QName sourceName, final boolean fromSavedValues) {
        this.sourceTargetValueClass = Objects.requireNonNull(sourceTargetValueClass);
        this.sourceName = Objects.requireNonNull(sourceName);
        this.fromSavedValues = fromSavedValues;
        return;
      }

    } // InjectedTargetElementParser.InjectionSpec

    protected static abstract class SingleValuedInjectionSpec<@NonNull ET,@NonNull ST> extends InjectionSpec<ET,ST> {

      protected SingleValuedInjectionSpec(final Class<ST> sourceTargetValueClass, final @Nullable QName sourceName, final boolean fromSavedValues) {
        super(sourceTargetValueClass, sourceName, fromSavedValues);
        return;
      }

    } // InjectedTargetElementParser.SingleValuedInjectionSpec

    protected static class ObjectInjectionSpec<@NonNull ET,@NonNull ST> extends SingleValuedInjectionSpec<ET,ST> {

      protected ObjectInjectionSpec(final Class<ST> sourceTargetValueClass, final @Nullable QName sourceName, final boolean fromSavedValues) {
        super(sourceTargetValueClass, sourceName, fromSavedValues);
        return;
      }

      @Override
      public @Nullable Object apply(final ElementParsingContext<ET> ctx) {
        return fromSavedValues ? ctx.getSavedValueOrNull(sourceName, sourceTargetValueClass) : ctx.getChildValueOrNull(sourceName, sourceTargetValueClass);
      }

    } // InjectedTargetElementParser.ObjectInjectionSpec

    protected static class AttrInjectionSpec<@NonNull ET> extends SingleValuedInjectionSpec<ET,String> {
      protected final Function<? super String,?> targetValueFunction;

      protected AttrInjectionSpec(final QName sourceName, final @Nullable Function<? super String,?> targetValueFunction) {
        super(String.class, sourceName, false);
        this.targetValueFunction = (targetValueFunction != null) ? targetValueFunction : Function.identity();
        return;
      }

      @Override
      public @Nullable Object apply(final ElementParsingContext<ET> ctx) {
        final @Nullable String attr = ctx.getAttrOrNull(Objects.requireNonNull(sourceName));
        return (attr != null) ? targetValueFunction.apply(attr) : null;
      }

    } // InjectedTargetElementParser.AttrInjectionSpec

    protected static abstract class MultiValuedInjectionSpec<@NonNull ET,@NonNull ST> extends InjectionSpec<ET,ST> {

      protected MultiValuedInjectionSpec(final Class<ST> sourceTargetValueClass, final @Nullable QName sourceName, final boolean fromSavedValues) {
        super(sourceTargetValueClass, sourceName, fromSavedValues);
        return;
      }

    } // InjectedTargetElementParser.MultiValuedInjectionSpec

    protected static class ArrayInjectionSpec<@NonNull ET,@NonNull ST> extends MultiValuedInjectionSpec<ET,ST> {

      protected ArrayInjectionSpec(final Class<ST> sourceTargetValueClass, final @Nullable QName sourceName, final boolean fromSavedValues) {
        super(sourceTargetValueClass, sourceName, fromSavedValues);
        return;
      }

      @Override
      public @Nullable Object apply(final ElementParsingContext<ET> ctx) {
        return (fromSavedValues ? ctx.getSavedValues(sourceName, sourceTargetValueClass) : ctx.getChildValues(sourceName, sourceTargetValueClass)).toArray((n) -> (Object[])java.lang.reflect.Array.newInstance(sourceTargetValueClass, n));
      }

    } // InjectedTargetElementParser.ArrayInjectionSpec

    protected static class ListInjectionSpec<@NonNull ET,@NonNull ST> extends MultiValuedInjectionSpec<ET,ST> {

      protected ListInjectionSpec(final Class<ST> sourceTargetValueClass, final @Nullable QName sourceName, final boolean fromSavedValues) {
        super(sourceTargetValueClass, sourceName, fromSavedValues);
        return;
      }

      @Override
      public @Nullable Object apply(final ElementParsingContext<ET> ctx) {
        return (fromSavedValues ? ctx.getSavedValues(sourceName, sourceTargetValueClass) : ctx.getChildValues(sourceName, sourceTargetValueClass)).collect(Collectors.toList());
      }

    } // InjectedTargetElementParser.ListInjectionSpec

    protected static class SetInjectionSpec<@NonNull ET,@NonNull ST> extends MultiValuedInjectionSpec<ET,ST> {

      protected SetInjectionSpec(final Class<ST> sourceTargetValueClass, final @Nullable QName sourceName, final boolean fromSavedValues) {
        super(sourceTargetValueClass, sourceName, fromSavedValues);
        return;
      }

      @Override
      public @Nullable Object apply(final ElementParsingContext<ET> ctx) {
        return (fromSavedValues ? ctx.getSavedValues(sourceName, sourceTargetValueClass) : ctx.getChildValues(sourceName, sourceTargetValueClass)).collect(Collectors.toSet());
      }

    } // InjectedTargetElementParser.SetInjectionSpec

  } // InjectedTargetElementParser

  /**
   * <p>
   * This class allows you to define the elements used within your XML documents and then
   * {@linkplain #createParser(Class, QName, QName) create a parser} for them.
   * </p>
   * 
   * <p>
   * XML uses a tree structure, and you use this class to define the schema for your XML from the bottom up, starting
   * with the "leaf" nodes, and then {@linkplain ChildElementListBuilder#addReferencedElementAsChild(QName, Class)
   * referencing} those when defining the parent elements which utilize those leaves as their own children, and so on
   * and so forth, working your way up to the root document element. All element definitions map their content to some
   * type of {@linkplain XMLStreamParser#getTargetValueClass() target value}. This builder class allows you to define
   * several types of elements within your schema. The first are low-level leaf-type
   * {@linkplain #defineSimpleElement(String, Class, BiFunction, boolean) simple} elements, and the generic
   * {@linkplain #defineStringElement(String, boolean) string} version. Next, the most common, are
   * {@linkplain #defineElement(String, Class, Function) typed elements}, for binding to your own data model. And then,
   * finally, are {@linkplain #defineWrapperElement(String, QName, Class) wrapper} and
   * {@linkplain #defineContainerElementWithChildBuilder(String) container} elements, for housing the others and forming
   * the root of the document tree. Any elements encountered while parsing which have not been defined within your
   * schema will be silently ignored.
   * </p>
   *
   * <p>
   * All definition methods accept a {@linkplain QName#getLocalPart() local name}, where the element will be defined
   * using the {@linkplain #getNamespace() current namespace} of the builder, which, unless you have
   * {@linkplain #setNamespace(URI) changed} it, will be the URI specified when you
   * {@linkplain XMLStreamParser#buildSchema(URI) created} it.
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

    public final <@NonNull ET> SB importElementDefinition(final SchemaBuilder<?> fromSchemaBuilder, final QName elementName, final Class<ET> targetValueClass) {
      return addParser(fromSchemaBuilder.getParser(elementName, targetValueClass));
    }

    public final <@NonNull ET> SB importElementDefinition(final SchemaBuilder<?> fromSchemaBuilder, final String elementLocalName, final Class<ET> targetValueClass) {
      return importElementDefinition(fromSchemaBuilder, qn(elementLocalName), targetValueClass);
    }

    public final SB importElementDefinition(final SchemaBuilder<?> fromSchemaBuilder, final QName elementName) {
      return addParser(fromSchemaBuilder.getParser(elementName));
    }

    public final SB importElementDefinition(final SchemaBuilder<?> fromSchemaBuilder, final String elementLocalName) {
      return importElementDefinition(fromSchemaBuilder, qn(elementLocalName));
    }

    /**
     * Define a simple element of the form '<code>&lt;ElementName&gt;Value&lt;/ElementName&gt;</code>', with no child
     * elements (leaf node).
     * 
     * @param <ET> The type of target value which will be calculated from the defined element.
     * @param simpleElementLocalName The {@linkplain QName#getLocalPart() local name} of the element being defined (the
     * {@linkplain #getNamespace() current namespace} will be used).
     * @param targetValueClass The {@link Class} of target value which will be calculated when the defined element is
     * parsed.
     * @param targetFunction A BiFunction to be used to calculate the target value for the defined element from the
     * {@link ElementParsingContext} and a String containing the child {@linkplain Characters character} data.
     * @param saveTargetValue Should target values calculated for the defined element be saved and made available (via
     * the {@link ElementParsingContext}) to the target value calculation functions of all subsequent elements parsed
     * within the current document?
     * @return The builder this method was called on.
     */
    public final <@NonNull ET> SB defineSimpleElement(final String simpleElementLocalName, final Class<ET> targetValueClass, final BiFunction<ElementParsingContext<ET>,String,ET> targetFunction, final boolean saveTargetValue) {
      return addParser(new SimpleElementParser<ET>(targetValueClass, qn(simpleElementLocalName), targetFunction, saveTargetValue));
    }

    public final <@NonNull ET> SB defineSimpleElement(final String simpleElementLocalName, final Class<ET> targetValueClass, final Function<String,ET> targetFunction) {
      return addParser(new SimpleElementParser<ET>(targetValueClass, qn(simpleElementLocalName), targetFunction, false));
    }

    public final SB defineStringElement(final String stringElementLocalName, final boolean saveTargetValue) {
      return addParser(new StringElementParser(qn(stringElementLocalName), saveTargetValue));
    }

    public final SB defineStringElement(final String stringElementLocalName) {
      return defineStringElement(stringElementLocalName, false);
    }

    public final <@NonNull ET> SB defineElement(final String elementLocalName, final Class<ET> targetValueClass, final Function<ElementParsingContext<ET>,ET> targetValueFunction) {
      return addParser(new ElementParser<ET>(targetValueClass, qn(elementLocalName), targetValueFunction, false));
    }

    public final <@NonNull ET> ChildElementListBuilder<SB,@NonNull ?> defineElementWithChildBuilder(final String elementLocalName, final Class<ET> targetValueClass, final Function<ElementParsingContext<ET>,ET> targetFunction) {
      return new ChildElementListBuilder<SB,ElementParser<?>>(schemaBuilderType.cast(this), ElementParser.WILDCARD_CLASS, (childParsers) -> addParser(new ElementParser<ET>(targetValueClass, qn(elementLocalName), targetFunction, false, childParsers)));
    }

    public final <@NonNull ET> SB defineElementWithInjectedTarget(final String injectedElementLocalName, final Class<ET> targetValueClass) {
      return addParser(new InjectedTargetElementParser<ET>(targetValueClass, qn(injectedElementLocalName), false, null, null));
    }

    public final <@NonNull ET> SB defineElementWithInjectedTarget(final Class<ET> targetValueClass) {
      return defineElementWithInjectedTarget(targetValueClass.getSimpleName(), targetValueClass);
    }

    @SuppressWarnings("unchecked")
    public final <@NonNull ET> InjectedTargetElementBuilder<ET,@NonNull ? extends InjectedTargetElementBuilder<ET,@NonNull ?>> defineElementWithInjectedTargetBuilder(final String injectedElementLocalName, final Class<ET> targetValueClass) {
      return new InjectedTargetElementBuilder<>((Class<InjectedTargetElementBuilder<ET,?>>)(Object)InjectedTargetElementBuilder.class, qn(injectedElementLocalName), targetValueClass);
    }

    public final <@NonNull ET> InjectedTargetElementBuilder<ET,@NonNull ? extends InjectedTargetElementBuilder<ET,@NonNull ?>> defineElementWithInjectedTargetBuilder(final Class<ET> targetValueClass) {
      return defineElementWithInjectedTargetBuilder(targetValueClass.getSimpleName(), targetValueClass);
    }

    public final <@NonNull ET> SB defineWrapperElement(final String elementLocalName, final QName wrappedElementName, final Class<ET> wrappedElementTargetValueClass) throws NoSuchElementException {
      return addParser(new WrapperElementParser<ET>(qn(elementLocalName), getParser(wrappedElementName, wrappedElementTargetValueClass)));
    }

    public final SB defineWrapperElement(final String elementLocalName, final QName wrappedElementName) throws NoSuchElementException {
      return addParser(new WrapperElementParser<>(qn(elementLocalName), getParser(wrappedElementName)));
    }

    public final SB defineWrapperElement(final String elementLocalName, final String wrappedElementName) throws NoSuchElementException {
      return defineWrapperElement(elementLocalName, qn(wrappedElementName));
    }

    public final ChildElementListBuilder<SB,@NonNull ?> defineContainerElementWithChildBuilder(final String containerElementLocalName) {
      return new ChildElementListBuilder<SB,ElementParser<?>>(schemaBuilderType.cast(this), ElementParser.WILDCARD_CLASS, (childParsers) -> addParser(new ContainerElementParser(qn(containerElementLocalName), childParsers)));
    }

    public <@NonNull T> XMLStreamParser<T> createParser(final Class<T> targetValueClass, final QName documentElementName, final QName targetElementName) throws NoSuchElementException {
      return new XMLStreamParser<T>(targetValueClass, getParser(documentElementName), getParser(targetElementName, targetValueClass));
    }

    public <@NonNull T> XMLStreamParser<T> createParser(final Class<T> targetValueClass, final String documentElementName, final String targetElementName) throws NoSuchElementException {
      return createParser(targetValueClass, qn(documentElementName), qn(targetElementName));
    }

    public final class ChildElementListBuilder<@NonNull PB,@NonNull PT extends ElementParser<?>> {
      protected final PB parentBuilder;
      protected final Class<? extends ElementParser<?>> parserType;
      protected final Consumer<PT[]> consumer;
      protected final Set<ElementParser<?>> childParsers = new CopyOnWriteArraySet<>();

      public ChildElementListBuilder(final PB parentBuilder, final Class<PT> parserType, final Consumer<PT[]> consumer) {
        this.parentBuilder = Objects.requireNonNull(parentBuilder);
        this.parserType = Objects.requireNonNull(parserType);
        this.consumer = Objects.requireNonNull(consumer);
        return;
      }

      public <@NonNull CT> ChildElementListBuilder<PB,PT> addReferencedElementAsChild(final QName elementName, final Class<CT> targetValueClass) throws NoSuchElementException {
        childParsers.add(getParser(parserType, elementName, targetValueClass));
        return this;
      }

      public <@NonNull CT> ChildElementListBuilder<PB,PT> addReferencedElementAsChild(final String elementName, final Class<CT> targetValueClass) throws NoSuchElementException {
        return addReferencedElementAsChild(qn(elementName), targetValueClass);
      }

      public ChildElementListBuilder<PB,PT> addReferencedElementAsChild(final QName elementName) throws NoSuchElementException {
        childParsers.add(getParser(parserType, elementName));
        return this;
      }

      public ChildElementListBuilder<PB,PT> addReferencedElementAsChild(final String elementName) throws NoSuchElementException {
        return addReferencedElementAsChild(qn(elementName));
      }

      @SuppressWarnings("unchecked")
      public PB completeElementDefinition() {
        consumer.accept(childParsers.stream().toArray((n) -> (PT[])java.lang.reflect.Array.newInstance(parserType, n)));
        return parentBuilder;
      }

    } // ChildElementListBuilder

    public class InjectedTargetElementBuilder<@NonNull ET,@NonNull EB extends InjectedTargetElementBuilder<ET,@NonNull ?>> {
      protected final Class<? extends EB> elementBuilderType;
      protected final QName elementName;
      protected final Class<ET> targetValueClass;
      protected boolean saveTargetValue = false;
      protected final Set<ElementParser<?>> childParsers = new CopyOnWriteArraySet<>();
      protected final Map<String,InjectedTargetElementParser.InjectionSpec<ET,?>> injectionSpecs = new ConcurrentHashMap<>();

      protected InjectedTargetElementBuilder(final Class<? extends EB> elementBuilderType, final QName elementName, final Class<ET> targetValueClass) {
        this.elementBuilderType = Objects.requireNonNull(elementBuilderType);
        this.elementName = Objects.requireNonNull(elementName);
        this.targetValueClass = Objects.requireNonNull(targetValueClass);
        return;
      }

      public EB setSaveTargetValue(final boolean saveTargetValue) {
        this.saveTargetValue = saveTargetValue;
        return elementBuilderType.cast(this);
      }

      protected EB addAttrInjectionSpec(final String injectedFieldName, final QName attrName, final @Nullable Function<? super String,?> targetValueFunction) {
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.AttrInjectionSpec<>(attrName, targetValueFunction));
        return elementBuilderType.cast(this);
      }

      public EB injectAttr(final String injectedFieldName, final QName attrName, final Function<? super String,?> targetValueFunction) {
        return addAttrInjectionSpec(injectedFieldName, attrName, targetValueFunction);
      }

      public EB injectAttr(final String injectedFieldName, final String attrName, final Function<? super String,?> targetValueFunction) {
        return addAttrInjectionSpec(injectedFieldName, new QName(XMLConstants.NULL_NS_URI, attrName), targetValueFunction);
      }

      public EB injectAttr(final QName attrName, final Function<? super String,?> targetValueFunction) {
        return addAttrInjectionSpec(attrName.getLocalPart(), attrName, targetValueFunction);
      }

      public EB injectAttr(final String attrName, final Function<? super String,?> targetValueFunction) {
        return addAttrInjectionSpec(attrName, new QName(XMLConstants.NULL_NS_URI, attrName), targetValueFunction);
      }

      public EB injectAttr(final String injectedFieldName, final QName attrName) {
        return addAttrInjectionSpec(injectedFieldName, attrName, null);
      }

      public EB injectAttr(final String injectedFieldName, final String attrName) {
        return addAttrInjectionSpec(injectedFieldName, new QName(XMLConstants.NULL_NS_URI, attrName), null);
      }

      public <@NonNull CT> EB injectChildObject(final String injectedFieldName, final QName childElementName, final Class<CT> childElementTargetValueClass) throws NoSuchElementException {
        childParsers.add(getParser(childElementName, childElementTargetValueClass));
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.ObjectInjectionSpec<>(childElementTargetValueClass, childElementName, false));
        return elementBuilderType.cast(this);
      }

      public EB injectChildObject(final String injectedFieldName, final QName childElementName) throws NoSuchElementException {
        final ElementParser<?> childElementParser = getParser(childElementName);
        childParsers.add(childElementParser);
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.ObjectInjectionSpec<>(childElementParser.getTargetValueClass(), childElementName, false));
        return elementBuilderType.cast(this);
      }

      public EB injectChildObject(final String injectedFieldName, final String childElementName) throws NoSuchElementException {
        return injectChildObject(injectedFieldName, qn(childElementName));
      }

      public <@NonNull CT> EB injectChildArray(final String injectedFieldName, final QName childElementName, final Class<CT> childElementTargetValueClass) throws NoSuchElementException {
        childParsers.add(getParser(childElementName, childElementTargetValueClass));
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.ArrayInjectionSpec<>(childElementTargetValueClass, childElementName, false));
        return elementBuilderType.cast(this);
      }

      public EB injectChildArray(final String injectedFieldName, final QName childElementName) throws NoSuchElementException {
        final ElementParser<?> childElementParser = getParser(childElementName);
        childParsers.add(childElementParser);
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.ArrayInjectionSpec<>(childElementParser.getTargetValueClass(), childElementName, false));
        return elementBuilderType.cast(this);
      }

      public EB injectChildArray(final String injectedFieldName, final String childElementName) throws NoSuchElementException {
        return injectChildArray(injectedFieldName, qn(childElementName));
      }

      public <@NonNull CT> EB injectChildList(final String injectedFieldName, final QName childElementName, final Class<CT> childElementTargetValueClass) throws NoSuchElementException {
        childParsers.add(getParser(childElementName, childElementTargetValueClass));
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.ListInjectionSpec<>(childElementTargetValueClass, childElementName, false));
        return elementBuilderType.cast(this);
      }

      public EB injectChildList(final String injectedFieldName, final QName childElementName) throws NoSuchElementException {
        final ElementParser<?> childElementParser = getParser(childElementName);
        childParsers.add(childElementParser);
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.ListInjectionSpec<>(childElementParser.getTargetValueClass(), childElementName, false));
        return elementBuilderType.cast(this);
      }

      public EB injectChildList(final String injectedFieldName, final String childElementName) throws NoSuchElementException {
        return injectChildList(injectedFieldName, qn(childElementName));
      }

      public <@NonNull CT> EB injectChildSet(final String injectedFieldName, final QName childElementName, final Class<CT> childElementTargetValueClass) throws NoSuchElementException {
        childParsers.add(getParser(childElementName, childElementTargetValueClass));
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.SetInjectionSpec<>(childElementTargetValueClass, childElementName, false));
        return elementBuilderType.cast(this);
      }

      public EB injectChildSet(final String injectedFieldName, final QName childElementName) throws NoSuchElementException {
        final ElementParser<?> childElementParser = getParser(childElementName);
        childParsers.add(childElementParser);
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.SetInjectionSpec<>(childElementParser.getTargetValueClass(), childElementName, false));
        return elementBuilderType.cast(this);
      }

      public EB injectChildSet(final String injectedFieldName, final String childElementName) throws NoSuchElementException {
        return injectChildSet(injectedFieldName, qn(childElementName));
      }

      public <@NonNull ST> EB injectSavedObject(final String injectedFieldName, final QName savedElementName, final Class<ST> savedElementTargetClass) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.ObjectInjectionSpec<>(savedElementTargetClass, savedElementName, true));
        return elementBuilderType.cast(this);
      }

      public EB injectSavedObject(final String injectedFieldName, final QName savedElementName) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.ObjectInjectionSpec<>(getParser(savedElementName).getTargetValueClass(), savedElementName, true));
        return elementBuilderType.cast(this);
      }

      public EB injectSavedObject(final String injectedFieldName, final String savedElementName) throws NoSuchElementException {
        return injectSavedObject(injectedFieldName, qn(savedElementName));
      }

      public <@NonNull ST> EB injectSavedArray(final String injectedFieldName, final QName savedElementName, final Class<ST> savedElementTargetClass) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.ArrayInjectionSpec<>(savedElementTargetClass, savedElementName, true));
        return elementBuilderType.cast(this);
      }

      public EB injectSavedArray(final String injectedFieldName, final QName savedElementName) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.ArrayInjectionSpec<>(getParser(savedElementName).getTargetValueClass(), savedElementName, true));
        return elementBuilderType.cast(this);
      }

      public EB injectSavedArray(final String injectedFieldName, final String savedElementName) throws NoSuchElementException {
        return injectSavedArray(injectedFieldName, qn(savedElementName));
      }

      public <@NonNull ST> EB injectSavedList(final String injectedFieldName, final QName savedElementName, final Class<ST> savedElementTargetClass) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.ListInjectionSpec<>(savedElementTargetClass, savedElementName, true));
        return elementBuilderType.cast(this);
      }

      public EB injectSavedList(final String injectedFieldName, final QName savedElementName) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.ListInjectionSpec<>(getParser(savedElementName).getTargetValueClass(), savedElementName, true));
        return elementBuilderType.cast(this);
      }

      public EB injectSavedList(final String injectedFieldName, final String savedElementName) throws NoSuchElementException {
        return injectSavedList(injectedFieldName, qn(savedElementName));
      }

      public <@NonNull ST> EB injectSavedSet(final String injectedFieldName, final QName savedElementName, final Class<ST> savedElementTargetClass) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.SetInjectionSpec<>(savedElementTargetClass, savedElementName, true));
        return elementBuilderType.cast(this);
      }

      public EB injectSavedSet(final String injectedFieldName, final QName savedElementName) throws NoSuchElementException {
        injectionSpecs.put(injectedFieldName, new InjectedTargetElementParser.SetInjectionSpec<>(getParser(savedElementName).getTargetValueClass(), savedElementName, true));
        return elementBuilderType.cast(this);
      }

      public EB injectSavedSet(final String injectedFieldName, final String savedElementName) throws NoSuchElementException {
        return injectSavedSet(injectedFieldName, qn(savedElementName));
      }

      public SB completeElementDefinition() {
        return addParser(new InjectedTargetElementParser<ET>(targetValueClass, elementName, saveTargetValue, childParsers, injectionSpecs));
      }

    } // SchemaBuilder.InjectedTargetElementBuilder

  } // SchemaBuilder

}
