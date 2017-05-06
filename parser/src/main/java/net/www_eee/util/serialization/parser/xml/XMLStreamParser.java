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
 * You {@linkplain #create(URI) create} a set of parsers you wish to capture from an {@linkplain XMLEventReader XML
 * event stream}, and this class provides a {@link Stream} implementation which will read/parse/construct them
 * dynamically as they are retrieved.
 *
 * @param <T> The type of target objects to be streamed.
 */
@NonNullByDefault
public class XMLStreamParser<@NonNull T> {
  private static final XMLInputFactory XML_INPUT_FACTORY = XMLInputFactory.newInstance();
  protected final Class<T> targetClass;
  protected final Map<QName,ElementParser<?>> elementParsers;
  private final ElementParser<?> documentParser;
  private final ElementParser<T> targetParser;

  protected XMLStreamParser(final Class<T> targetClass, final Collection<ElementParser<?>> elementParsers, final QName documentElementName, final QName targetElementName) throws NoSuchElementException, ClassCastException {
    this.targetClass = Objects.requireNonNull(targetClass, "null targetClass");
    this.elementParsers = Collections.unmodifiableMap(new ConcurrentHashMap<>(Objects.requireNonNull(elementParsers, "null elementParsers").stream().collect(Collectors.<ElementParser<?>,QName,ElementParser<?>> toMap(ElementParser::getElementName, Function.identity()))));
    this.documentParser = Optional.ofNullable(this.elementParsers.get(Objects.requireNonNull(documentElementName, "null documentElementName"))).orElseThrow(() -> new NoSuchElementException("No parser supplied for document element " + documentElementName));
    final ElementParser<?> tp1 = Optional.ofNullable(this.elementParsers.get(Objects.requireNonNull(targetElementName, "null targetElementName"))).orElseThrow(() -> new NoSuchElementException("No parser supplied for target element " + targetElementName));
    if (!targetClass.isAssignableFrom(tp1.getTargetClass())) throw new ClassCastException("Specified target parser results ('" + tp1.getTargetClass() + "') are incompatible with target class ('" + targetClass + "')");
    @SuppressWarnings("unchecked")
    final ElementParser<T> tp2 = (ElementParser<T>)tp1;
    this.targetParser = tp2;
    return;
  }

  public Class<T> getTargetClass() {
    return targetClass;
  }

  public final Stream<T> parse(final InputStream inputStream) throws ParsingException {
    try {
      final XMLEventReader reader = XML_INPUT_FACTORY.createXMLEventReader(inputStream);

      ElementParser<?>.ParsingContextImpl targetParentContext = null;
      try {
        documentParser.parse(null, reader.nextTag(), reader, targetParser); // Read in events up until an element using the targetParser is encountered.
      } catch (TerminatingParserException tpe) {
        targetParentContext = tpe.getParsingContextImpl();
      }

      return (targetParentContext != null) ? StreamSupport.stream(new TargetSpliterator(targetParentContext, reader), false) : Stream.empty();
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

  @SuppressWarnings("unchecked")
  public static SchemaBuilder<@NonNull ? extends SchemaBuilder<@NonNull ?>> create(final @Nullable URI namespace) {
    return new SchemaBuilder<>((Class<SchemaBuilder<?>>)(Object)SchemaBuilder.class, namespace, null);
  }

  @SuppressWarnings("unchecked")
  protected static final <@NonNull T> ElementParser<T>.ParsingContextImpl cast(final ElementParsingContext<T> ctx) {
    return (ElementParser<T>.ParsingContextImpl)(Object)ctx;
  }

  /**
   * An implementation of this interface is the sole parameter to a {@link Function} you supply for translating element
   * content into target objects.
   */
  public interface ElementParsingContext<@NonNull T> {

    public Class<T> type();

    public QName name();

    public default String ns() {
      return name().getNamespaceURI();
    }

    public StartElement event();

    @SuppressWarnings("unchecked")
    public default Map<String,String> attrs() {
      return Collections.unmodifiableMap(StreamSupport.stream(Spliterators.spliteratorUnknownSize((Iterator<Attribute>)event().getAttributes(), Spliterator.NONNULL | Spliterator.DISTINCT | Spliterator.IMMUTABLE), false).map((attr) -> new AbstractMap.SimpleImmutableEntry<>(attr.getName().getLocalPart(), attr.getValue())).collect(Collectors.<Map.Entry<String,String>,String,String> toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    public default @Nullable String attrNull(final QName name) {
      final @Nullable Attribute attr = event().getAttributeByName(name);
      return (attr != null) ? attr.getValue() : null;
    }

    public default <A> @Nullable A attrNull(final QName name, final Function<? super String,? extends A> targetFunction) {
      final @Nullable String attr = attrNull(name);
      return (attr != null) ? targetFunction.apply(attr) : null;
    }

    public default @Nullable String attrNull(final String localName) {
      return attrNull(new QName(XMLConstants.NULL_NS_URI, localName));
    }

    public default <A> @Nullable A attrNull(final String localName, final Function<? super String,? extends A> targetFunction) {
      final @Nullable String attr = attrNull(localName);
      return (attr != null) ? targetFunction.apply(attr) : null;
    }

    public default Optional<String> attrOpt(final QName name) {
      return Optional.ofNullable(event().getAttributeByName(name)).map(Attribute::getValue);
    }

    public default <A> Optional<A> attrOpt(final QName name, final Function<? super String,? extends A> targetFunction) {
      return attrOpt(name).map(targetFunction);
    }

    public default Optional<String> attrOpt(final String localName) {
      return attrOpt(new QName(XMLConstants.NULL_NS_URI, localName));
    }

    public default <A> Optional<A> attrOpt(final String localName, final Function<? super String,? extends A> targetFunction) {
      return attrOpt(localName).map(targetFunction);
    }

    public default String attr(final QName name) throws NoSuchElementException {
      return attrOpt(name).orElseThrow(() -> new NoSuchElementException("Element '" + name().getLocalPart() + "' has no '" + name.getLocalPart() + "' attribute"));
    }

    public default <A> A attr(final QName name, final Function<? super String,? extends A> targetFunction) throws NoSuchElementException {
      return targetFunction.apply(attr(name));
    }

    public default String attr(final String localName) throws NoSuchElementException {
      return attr(new QName(XMLConstants.NULL_NS_URI, localName));
    }

    public default <A> A attr(final String localName, final Function<? super String,? extends A> targetFunction) throws NoSuchElementException {
      return targetFunction.apply(attr(localName));
    }

    public Deque<StartElement> eventStack();

    public <@NonNull S> Stream<S> saved(final @Nullable QName name, final Class<S> valueClass);

    public default <@NonNull S> Stream<S> saved(final @Nullable String localName, final Class<S> valueClass) {
      return saved((localName != null) ? new QName(ns(), localName) : null, valueClass);
    }

    public default <@NonNull S> Optional<S> savedFirstOpt(final @Nullable QName name, final Class<S> valueClass) throws NoSuchElementException {
      return saved(name, valueClass).findFirst();
    }

    public default <@NonNull S> Optional<S> savedFirstOpt(final @Nullable String localName, final Class<S> valueClass) throws NoSuchElementException {
      return savedFirstOpt((localName != null) ? new QName(ns(), localName) : null, valueClass);
    }

    public default <@NonNull S> @Nullable S savedFirstNull(final @Nullable QName name, final Class<S> valueClass) throws NoSuchElementException {
      final Optional<S> saved = savedFirstOpt(name, valueClass);
      return saved.isPresent() ? saved.get() : null;
    }

    public default <@NonNull S> @Nullable S savedFirstNull(final @Nullable String localName, final Class<S> valueClass) throws NoSuchElementException {
      final Optional<S> saved = savedFirstOpt(localName, valueClass);
      return saved.isPresent() ? saved.get() : null;
    }

    public default <@NonNull S> S savedFirst(final @Nullable QName name, final Class<S> valueClass) throws NoSuchElementException {
      return savedFirstOpt(name, valueClass).orElseThrow(() -> new NoSuchElementException("No " + ((name != null) ? "'" + name.getLocalPart() + "' " : "") + " saved element with '" + valueClass.getSimpleName() + "' type value found"));
    }

    public default <@NonNull S> S savedFirst(final @Nullable String localName, final Class<S> valueClass) throws NoSuchElementException {
      return savedFirst((localName != null) ? new QName(ns(), localName) : null, valueClass);
    }

    public Stream<Map.Entry<Map.Entry<QName,Class<?>>,List<?>>> children();

    public default <@NonNull ET> Stream<ET> children(final @Nullable QName name, final Class<ET> valueClass) {
      return children().filter((entry) -> (name == null) || (name.equals(entry.getKey().getKey()))).filter((entry) -> valueClass.isAssignableFrom(entry.getKey().getValue())).<List<?>> map(Map.Entry::getValue).flatMap(List::stream).map((value) -> valueClass.cast(value));
    }

    public default <@NonNull ET> Stream<ET> children(final @Nullable String localName, final Class<ET> valueClass) {
      return children((localName != null) ? new QName(ns(), localName) : null, valueClass);
    }

    public default <@NonNull ET> Optional<ET> childOpt(final @Nullable QName name, final Class<ET> valueClass) {
      return children(name, valueClass).findFirst();
    }

    public default <@NonNull ET> Optional<ET> childOpt(final @Nullable String localName, final Class<ET> valueClass) {
      return childOpt((localName != null) ? new QName(ns(), localName) : null, valueClass);
    }

    public default <@NonNull ET> @Nullable ET childNull(final @Nullable QName name, final Class<ET> valueClass) {
      final Optional<ET> child = childOpt(name, valueClass);
      return child.isPresent() ? child.get() : null;
    }

    public default <@NonNull ET> @Nullable ET childNull(final @Nullable String localName, final Class<ET> valueClass) {
      final Optional<ET> child = childOpt(localName, valueClass);
      return child.isPresent() ? child.get() : null;
    }

    public default <@NonNull ET> ET child(final @Nullable QName name, final Class<ET> valueClass) throws NoSuchElementException {
      return childOpt(name, valueClass).orElseThrow(() -> new NoSuchElementException("No " + ((name != null) ? "'" + name.getLocalPart() + "' " : "") + " child element with '" + valueClass.getSimpleName() + "' type value found"));
    }

    public default <@NonNull ET> ET child(final @Nullable String localName, final Class<ET> valueClass) throws NoSuchElementException {
      return child((localName != null) ? new QName(ns(), localName) : null, valueClass);
    }

  } // ElementParsingContext

  private final class TargetSpliterator implements Spliterator<T> {
    private final int CHARACTERISTICS = 0 | Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.NONNULL | Spliterator.IMMUTABLE;
    private final ElementParser<?>.ParsingContextImpl parentContext;
    private final XMLEventReader reader;

    public TargetSpliterator(final ElementParser<?>.ParsingContextImpl parentContext, final XMLEventReader reader) throws IllegalArgumentException {
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

  } // TargetSpliterator

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

  public static class ElementValueParsingException extends ContextualParsingException {

    protected ElementValueParsingException(final Exception cause, final ElementParser<?>.ParsingContextImpl context) {
      super(cause.getClass().getName() + " parsing '" + context.event().getName().getLocalPart() + "' element: " + cause.getMessage(), Objects.requireNonNull(cause, "null cause"), context);
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
   * parsing} and return the current {@linkplain #getParsingContextImpl() parsing context} when the specified
   * {@linkplain XMLStreamParser.ElementParser element} encountered.
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
    protected final Class<T> targetClass;

    protected ContentParser(final Class<E> eventClass, final Class<T> targetClass) {
      this.eventClass = eventClass;
      this.targetClass = targetClass;
      return;
    }

    public final Class<E> getEventClass() {
      return eventClass;
    }

    public final Class<T> getTargetClass() {
      return targetClass;
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
    private static final Class<ElementParser<?>> WILDCARD_CLASS = (Class<ElementParser<?>>)(Object)ElementParser.class;
    protected final QName elementName;
    private final Function<ElementParsingContext<T>,T> targetFunction;
    protected final boolean saveTargetValue;
    private final Set<? extends ContentParser<?,?>> childParsers;

    public ElementParser(final Class<T> targetClass, final QName elementName, final Function<ElementParsingContext<T>,T> targetFunction, final boolean saveTargetValue, final @Nullable Collection<? extends ContentParser<?,?>> childParsers) {
      super(StartElement.class, targetClass);
      this.elementName = elementName;
      this.targetFunction = targetFunction;
      this.saveTargetValue = saveTargetValue;
      this.childParsers = (childParsers != null) ? Collections.unmodifiableSet(new CopyOnWriteArraySet<>(childParsers)) : Collections.emptySet();
      return;
    }

    public ElementParser(final Class<T> targetClass, final QName elementName, final Function<ElementParsingContext<T>,T> targetFunction, final boolean saveTargetValue, final @NonNull ContentParser<?,?> @Nullable... childParsers) {
      this(targetClass, elementName, targetFunction, saveTargetValue, (childParsers != null) ? Arrays.asList(childParsers) : null);
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
      final T value;
      try {
        value = targetFunction.apply(context);
      } catch (ElementValueParsingException evpe) {
        throw evpe;
      } catch (RuntimeException re) {
        throw new ElementValueParsingException(re, context);
      }
      if (saveTargetValue) context.saveValue(value);
      return value;
    }

    @Override
    public String toString() {
      return '<' + elementName.toString() + '>';
    }

    private static final Stream<Map.Entry<ElementParser<?>,List<Object>>> getElements(final Stream<? extends Map.Entry<? extends ContentParser<?,?>,List<Object>>> values) {
      return values.filter((entry) -> ElementParser.class.isInstance(entry.getKey())).<Map.Entry<ElementParser<?>,List<Object>>> map((entry) -> new AbstractMap.SimpleImmutableEntry<>(ElementParser.class.cast(entry.getKey()), entry.getValue()));
    }

    private static final <@NonNull ET> Stream<ET> getElements(final Stream<? extends Map.Entry<? extends ContentParser<?,?>,List<Object>>> values, final @Nullable QName name, final Class<ET> valueClass) {
      return getElements(values).filter((entry) -> (name == null) || name.equals(entry.getKey().getElementName())).filter((entry) -> valueClass.isAssignableFrom(entry.getKey().getTargetClass())).map(Map.Entry::getValue).flatMap(List::stream).map(valueClass::cast);
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
      public Class<T> type() {
        return targetClass;
      }

      @Override
      public final QName name() {
        return elementName;
      }

      @Override
      public StartElement event() {
        return startElement;
      }

      private int getDepth() {
        final ElementParser<?>.@Nullable ParsingContextImpl pc = parentContext;
        if (pc == null) return 0;
        return pc.getDepth() + 1;
      }

      private Deque<StartElement> getElementContextImpl(Deque<StartElement> elementStack) {
        if (parentContext != null) parentContext.getElementContextImpl(elementStack);
        elementStack.push(startElement);
        return elementStack;
      }

      @Override
      public Deque<StartElement> eventStack() {
        return getElementContextImpl(new ArrayDeque<>(getDepth() + 1));
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
            final Object child = parser.get().parse(this, parser.get().eventClass.cast(event), reader, terminatingParser);
            final List<Object> existingValues = childValues.get(parser.get());
            if (existingValues != null) {
              existingValues.add(child);
            } else {
              childValues.put(parser.get(), new CopyOnWriteArrayList<>(Collections.singleton(child)));
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

      public <@NonNull S> Stream<S> saved(final ElementParser<S> parser) {
        final List<Object> values = savedValues.get(ElementParser.this);
        return (values != null) ? values.stream().map(parser.getTargetClass()::cast) : Stream.empty();
      }

      public <@NonNull S> S savedFirst(final ElementParser<S> parser) throws NoSuchElementException {
        return saved(parser).findFirst().orElseThrow(() -> new NoSuchElementException(ElementParser.this.toString()));
      }

      public <@NonNull ET> Optional<ET> childOpt(final ContentParser<?,ET> parser) {
        return children(parser).findFirst();
      }

      public <@NonNull ET> ET child(final ContentParser<?,ET> parser) throws NoSuchElementException {
        return childOpt(parser).orElseThrow(() -> new NoSuchElementException(parser.toString()));
      }

      @Override
      public <@NonNull S> Stream<S> saved(final @Nullable QName name, final Class<S> valueClass) {
        return getElements(savedValues.entrySet().stream(), name, valueClass);
      }

      public <@NonNull ET> Stream<ET> children(final ContentParser<?,ET> parser) {
        return Optional.ofNullable(childValues.get(parser)).map(List::stream).orElse(Stream.empty()).map((v) -> parser.getTargetClass().cast(v));
      }

      @Override
      public Stream<Map.Entry<Map.Entry<QName,Class<?>>,List<?>>> children() {
        return getElements(childValues.entrySet().stream()).map((entry) -> new AbstractMap.SimpleImmutableEntry<>(new AbstractMap.SimpleImmutableEntry<>(entry.getKey().getElementName(), entry.getKey().getTargetClass()), entry.getValue()));
      }

    } // ElementParser.ParsingContextImpl

  } // ElementParser

  protected static class ContainerElementParser extends ElementParser<StartElement> {

    public ContainerElementParser(final QName name, final @NonNull ElementParser<?> @Nullable... childParsers) {
      super(StartElement.class, name, (context) -> context.event(), false, childParsers);
      return;
    }

  } // ContainerElementParser

  protected static class WrapperElementParser<@NonNull T> extends ElementParser<T> {

    public WrapperElementParser(final QName name, final ElementParser<T> wrappedElement) {
      super(wrappedElement.targetClass, name, (ctx) -> cast(ctx).child(wrappedElement), false, wrappedElement);
      return;
    }

  } // WrapperElementParser

  protected static class TextElementParser<@NonNull T> extends ElementParser<T> {
    private static final CharactersParser CHARACTERS_PARSER = new CharactersParser(true, true, false);

    public TextElementParser(final Class<T> targetClass, final QName name, final Function<? super String,? extends T> targetFunction, final boolean saveTargetValue) {
      super(targetClass, name, (ctx) -> targetFunction.apply(cast(ctx).children(CHARACTERS_PARSER).collect(Collectors.joining())), saveTargetValue, CHARACTERS_PARSER);
      return;
    }

  } // TextElementParser

  protected static class StringElementParser extends TextElementParser<String> {

    public StringElementParser(final QName name, final boolean saveTargetValue) {
      super(String.class, name, Function.identity(), saveTargetValue);
      return;
    }

  } // StringElementParser

  protected static class InjectedElementParser<@NonNull T> extends ElementParser<T> {
    protected static final DSLContext DSL_CONTEXT = DSL.using(SQLDialect.DEFAULT);

    public InjectedElementParser(final Class<T> targetClass, final QName elementName, final boolean saveTargetValue, final @Nullable Collection<? extends ElementParser<?>> childParsers, final @Nullable Map<String,? extends InjectionSpec<T,?>> injectionSpecs) throws IllegalArgumentException {
      super(targetClass, elementName, (ctx) -> inject(targetClass, ctx, injectionSpecs), saveTargetValue, childParsers);
      return;
    }

    protected static final <@NonNull T> T inject(final Class<? extends T> targetClass, final ElementParsingContext<T> ctx, final @Nullable Map<String,? extends InjectionSpec<T,?>> injectionSpecs) throws ElementValueParsingException {
      final Map<String,Field<Object>> fields = ((injectionSpecs != null) ? Stream.concat(ctx.attrs().keySet().stream(), injectionSpecs.keySet().stream()) : ctx.attrs().keySet().stream()).distinct().map((injectedFieldName) -> new AbstractMap.SimpleImmutableEntry<>(injectedFieldName, DSL.field(DSL.name(injectedFieldName)))).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
      final Record record = DSL_CONTEXT.newRecord(fields.values().stream().toArray(Field<?>[]::new));
      ctx.attrs().entrySet().stream().forEach((attr) -> record.<Object> set(Objects.requireNonNull(fields.get(attr.getKey())), attr.getValue()));
      if (injectionSpecs != null) injectionSpecs.entrySet().stream().forEach((spec) -> record.<Object> set(Objects.requireNonNull(fields.get(spec.getKey())), spec.getValue().apply(ctx)));
      try {
        return record.into(targetClass);
      } catch (MappingException me) {
        throw new ElementValueParsingException(me, cast(ctx));
      }
    }

    protected static abstract class InjectionSpec<@NonNull ET,@NonNull ST> implements Function<ElementParsingContext<ET>,@Nullable Object> {
      protected final Class<ST> sourceClass;
      protected final @Nullable QName sourceName;
      protected final boolean fromSaved;

      protected InjectionSpec(final Class<ST> sourceClass, final @Nullable QName sourceName, final boolean fromSaved) {
        this.sourceClass = Objects.requireNonNull(sourceClass);
        this.sourceName = Objects.requireNonNull(sourceName);
        this.fromSaved = fromSaved;
        return;
      }

    } // InjectedElementParser.InjectionSpec

    protected static abstract class SingleValuedInjectionSpec<@NonNull ET,@NonNull ST> extends InjectionSpec<ET,ST> {

      protected SingleValuedInjectionSpec(final Class<ST> sourceClass, final @Nullable QName sourceName, final boolean fromSaved) {
        super(sourceClass, sourceName, fromSaved);
        return;
      }

    } // InjectedElementParser.SingleValuedInjectionSpec

    protected static class ObjectInjectionSpec<@NonNull ET,@NonNull ST> extends SingleValuedInjectionSpec<ET,ST> {

      protected ObjectInjectionSpec(final Class<ST> sourceClass, final @Nullable QName sourceName, final boolean fromSaved) {
        super(sourceClass, sourceName, fromSaved);
        return;
      }

      @Override
      public @Nullable Object apply(final ElementParsingContext<ET> ctx) {
        return fromSaved ? ctx.savedFirstNull(sourceName, sourceClass) : ctx.childNull(sourceName, sourceClass);
      }

    } // InjectedElementParser.ObjectInjectionSpec

    protected static class AttrInjectionSpec<@NonNull ET> extends SingleValuedInjectionSpec<ET,String> {
      protected final Function<? super String,?> targetFunction;

      protected AttrInjectionSpec(final QName sourceName, final @Nullable Function<? super String,?> targetFunction) {
        super(String.class, sourceName, false);
        this.targetFunction = (targetFunction != null) ? targetFunction : Function.identity();
        return;
      }

      @Override
      public @Nullable Object apply(final ElementParsingContext<ET> ctx) {
        final @Nullable String attr = ctx.attrNull(Objects.requireNonNull(sourceName));
        return (attr != null) ? targetFunction.apply(attr) : null;
      }

    } // InjectedElementParser.AttrInjectionSpec

    protected static abstract class MultiValuedInjectionSpec<@NonNull ET,@NonNull ST> extends InjectionSpec<ET,ST> {

      protected MultiValuedInjectionSpec(final Class<ST> sourceClass, final @Nullable QName sourceName, final boolean fromSaved) {
        super(sourceClass, sourceName, fromSaved);
        return;
      }

    } // InjectedElementParser.MultiValuedInjectionSpec

    protected static class ArrayInjectionSpec<@NonNull ET,@NonNull ST> extends MultiValuedInjectionSpec<ET,ST> {

      protected ArrayInjectionSpec(final Class<ST> sourceClass, final @Nullable QName sourceName, final boolean fromSaved) {
        super(sourceClass, sourceName, fromSaved);
        return;
      }

      @Override
      public @Nullable Object apply(final ElementParsingContext<ET> ctx) {
        return (fromSaved ? ctx.saved(sourceName, sourceClass) : ctx.children(sourceName, sourceClass)).toArray((n) -> (Object[])java.lang.reflect.Array.newInstance(sourceClass, n));
      }

    } // InjectedElementParser.ArrayInjectionSpec

    protected static class ListInjectionSpec<@NonNull ET,@NonNull ST> extends MultiValuedInjectionSpec<ET,ST> {

      protected ListInjectionSpec(final Class<ST> sourceClass, final @Nullable QName sourceName, final boolean fromSaved) {
        super(sourceClass, sourceName, fromSaved);
        return;
      }

      @Override
      public @Nullable Object apply(final ElementParsingContext<ET> ctx) {
        return (fromSaved ? ctx.saved(sourceName, sourceClass) : ctx.children(sourceName, sourceClass)).collect(Collectors.toList());
      }

    } // InjectedElementParser.ListInjectionSpec

    protected static class SetInjectionSpec<@NonNull ET,@NonNull ST> extends MultiValuedInjectionSpec<ET,ST> {

      protected SetInjectionSpec(final Class<ST> sourceClass, final @Nullable QName sourceName, final boolean fromSaved) {
        super(sourceClass, sourceName, fromSaved);
        return;
      }

      @Override
      public @Nullable Object apply(final ElementParsingContext<ET> ctx) {
        return (fromSaved ? ctx.saved(sourceName, sourceClass) : ctx.children(sourceName, sourceClass)).collect(Collectors.toSet());
      }

    } // InjectedElementParser.SetInjectionSpec

  } // InjectedElementParser

  public static class SchemaBuilder<@NonNull SB extends SchemaBuilder<@NonNull ?>> {
    protected final Class<? extends SB> schemaBuilderType;
    protected final @Nullable URI namespace;
    protected final Map<QName,ElementParser<?>> elementParsers;

    protected SchemaBuilder(final Class<? extends SB> schemaBuilderType, final @Nullable URI namespace, final @Nullable Map<QName,ElementParser<?>> elementParsers) {
      this.schemaBuilderType = Objects.requireNonNull(schemaBuilderType);
      this.namespace = namespace;
      this.elementParsers = (elementParsers != null) ? new HashMap<>(elementParsers) : new HashMap<>();
      return;
    }

    protected SB forkImpl(final @Nullable URI namespace) {
      return schemaBuilderType.cast(new SchemaBuilder<SB>(schemaBuilderType, namespace, elementParsers));
    }

    public final SB fork() {
      return forkImpl(namespace);
    }

    public final SB namespace(final @Nullable URI namespace) {
      return forkImpl(namespace);
    }

    public final QName qn(final String localName) {
      return new QName(Optional.ofNullable(namespace).map(URI::toString).orElse(XMLConstants.NULL_NS_URI), localName);
    }

    public final @NonNull QName[] qns(final @NonNull String @Nullable... localNames) {
      return ((localNames != null) ? Arrays.<String> asList(localNames) : Collections.<String> emptyList()).stream().map(this::qn).toArray((n) -> new @NonNull QName[n]);
    }

    protected SB add(final ElementParser<?> elementParser) {
      if (elementParsers.containsKey(elementParser.getElementName())) throw new IllegalArgumentException("Attempt to add duplicate '" + elementParser.getElementName() + "' parser");
      elementParsers.put(elementParser.getElementName(), elementParser);
      return schemaBuilderType.cast(this);
    }

    protected final <E extends ElementParser<?>> E getParser(final Class<E> type, final QName name) throws NoSuchElementException, ClassCastException {
      return type.cast(Optional.ofNullable(elementParsers.get(name)).orElseThrow(() -> new NoSuchElementException("No '" + name + "' parser found")));
    }

    protected final @NonNull ElementParser<?>[] getParsers(final @NonNull QName @Nullable... names) throws NoSuchElementException, ClassCastException {
      return ((names != null) ? Arrays.<QName> asList(names) : Collections.<QName> emptyList()).stream().<ElementParser<?>> map((qn) -> getParser(ElementParser.WILDCARD_CLASS, qn)).toArray((n) -> new ElementParser<?>[n]);
    }

    public final <@NonNull ET> SB element(final String localName, final Class<ET> targetClass, final Function<ElementParsingContext<ET>,ET> targetFunction, final boolean saveTargetValue, final @NonNull QName @Nullable... childElementNames) throws NoSuchElementException, ClassCastException {
      return add(new ElementParser<ET>(targetClass, qn(localName), targetFunction, saveTargetValue, getParsers(childElementNames)));
    }

    public final <@NonNull ET> SB element(final String localName, final Class<ET> targetClass, final Function<ElementParsingContext<ET>,ET> targetFunction, final @NonNull String @Nullable... childElementNames) throws NoSuchElementException, ClassCastException {
      return element(localName, targetClass, targetFunction, false, qns(childElementNames));
    }

    public final SB container(final String localName, final @NonNull QName... childElementNames) throws NoSuchElementException, ClassCastException {
      return add(new ContainerElementParser(qn(localName), getParsers(childElementNames)));
    }

    public final SB container(final String localName, final @NonNull String... childElementNames) throws NoSuchElementException, ClassCastException {
      return container(localName, qns(childElementNames));
    }

    public final <@NonNull ET> SB text(final String localName, final Class<ET> targetClass, final Function<String,ET> targetFunction, final boolean saveTargetValue) {
      return add(new TextElementParser<ET>(targetClass, qn(localName), targetFunction, saveTargetValue));
    }

    public final <@NonNull ET> SB text(final String localName, final Class<ET> targetClass, final Function<String,ET> targetFunction) {
      return text(localName, targetClass, targetFunction, false);
    }

    public final SB string(final String localName, final boolean saveTargetValue) {
      return add(new StringElementParser(qn(localName), saveTargetValue));
    }

    public final SB string(final String localName) {
      return string(localName, false);
    }

    public final <@NonNull ET> SB injected(final QName elementName, final Class<ET> targetClass) {
      return add(new InjectedElementParser<ET>(targetClass, elementName, false, null, null));
    }

    public final <@NonNull ET> SB injected(final String localName, final Class<ET> targetClass) {
      return injected(qn(localName), targetClass);
    }

    public final <@NonNull ET> SB injected(final Class<ET> targetClass) {
      return injected(targetClass.getSimpleName(), targetClass);
    }

    @SuppressWarnings("unchecked")
    public final <@NonNull ET> InjectedElementBuilder<ET,@NonNull ? extends InjectedElementBuilder<ET,@NonNull ?>> injectedBuilder(final QName elementName, final Class<ET> targetClass) {
      return new InjectedElementBuilder<>((Class<InjectedElementBuilder<ET,?>>)(Object)InjectedElementBuilder.class, elementName, targetClass);
    }

    public final <@NonNull ET> InjectedElementBuilder<ET,@NonNull ? extends InjectedElementBuilder<ET,@NonNull ?>> injectedBuilder(final String localName, final Class<ET> targetClass) {
      return injectedBuilder(qn(localName), targetClass);
    }

    public final <@NonNull ET> InjectedElementBuilder<ET,@NonNull ? extends InjectedElementBuilder<ET,@NonNull ?>> injectedBuilder(final Class<ET> targetClass) {
      return injectedBuilder(targetClass.getSimpleName(), targetClass);
    }

    public <@NonNull T> XMLStreamParser<T> parser(final Class<T> targetClass, final QName documentElementName, final QName targetElementName) throws NoSuchElementException, ClassCastException {
      return new XMLStreamParser<T>(targetClass, elementParsers.values(), documentElementName, targetElementName);
    }

    public <@NonNull T> XMLStreamParser<T> parser(final Class<T> targetClass, final String documentParserName, final String targetParserName) throws NoSuchElementException, ClassCastException {
      return parser(targetClass, qn(documentParserName), qn(targetParserName));
    }

    public class InjectedElementBuilder<@NonNull ET,@NonNull EB extends InjectedElementBuilder<ET,@NonNull ?>> {
      protected final Class<? extends EB> elementBuilderType;
      protected final QName elementName;
      protected final Class<ET> targetClass;
      protected boolean saveTargetValue = false;
      protected final Set<ElementParser<?>> childParsers = new HashSet<>();
      protected final Map<String,InjectedElementParser.InjectionSpec<ET,?>> injectionSpecs = new ConcurrentHashMap<>();

      protected InjectedElementBuilder(final Class<? extends EB> elementBuilderType, final QName elementName, final Class<ET> targetClass) {
        this.elementBuilderType = Objects.requireNonNull(elementBuilderType);
        this.elementName = Objects.requireNonNull(elementName);
        this.targetClass = Objects.requireNonNull(targetClass);
        return;
      }

      public EB saveTargetValue(final boolean saveTargetValue) {
        this.saveTargetValue = saveTargetValue;
        return elementBuilderType.cast(this);
      }

      protected EB addAttrInjectionSpec(final String injectedFieldName, final QName attrName, final @Nullable Function<? super String,?> targetFunction) {
        injectionSpecs.put(injectedFieldName, new InjectedElementParser.AttrInjectionSpec<>(attrName, targetFunction));
        return elementBuilderType.cast(this);
      }

      public EB attr(final String injectedFieldName, final QName attrName, final Function<? super String,?> targetFunction) {
        return addAttrInjectionSpec(injectedFieldName, attrName, targetFunction);
      }

      public EB attr(final String injectedFieldName, final String attrName, final Function<? super String,?> targetFunction) {
        return addAttrInjectionSpec(injectedFieldName, new QName(XMLConstants.NULL_NS_URI, attrName), targetFunction);
      }

      public EB attr(final QName attrName, final Function<? super String,?> targetFunction) {
        return addAttrInjectionSpec(attrName.getLocalPart(), attrName, targetFunction);
      }

      public EB attr(final String attrName, final Function<? super String,?> targetFunction) {
        return addAttrInjectionSpec(attrName, new QName(XMLConstants.NULL_NS_URI, attrName), targetFunction);
      }

      public EB attr(final String injectedFieldName, final QName attrName) {
        return addAttrInjectionSpec(injectedFieldName, attrName, null);
      }

      public EB attr(final String injectedFieldName, final String attrName) {
        return addAttrInjectionSpec(injectedFieldName, new QName(XMLConstants.NULL_NS_URI, attrName), null);
      }

      @SuppressWarnings("unchecked")
      protected <@NonNull CT> EB addInjectionSpec(final String injectedFieldName, final QName sourceName, final boolean addChildParser, final Function<Class<CT>,InjectedElementParser.InjectionSpec<ET,CT>> injectionSpecCreator) throws NoSuchElementException {
        final ElementParser<?> injectedElementParser = getParser(ElementParser.WILDCARD_CLASS, sourceName);
        if (addChildParser) childParsers.add(injectedElementParser);
        injectionSpecs.put(injectedFieldName, injectionSpecCreator.apply((Class<CT>)injectedElementParser.getTargetClass()));
        return elementBuilderType.cast(this);
      }

      public EB child(final String injectedFieldName, final QName childElementName) throws NoSuchElementException {
        return addInjectionSpec(injectedFieldName, childElementName, true, (sourceClass) -> new InjectedElementParser.ObjectInjectionSpec<>(sourceClass, childElementName, false));
      }

      public EB child(final String injectedFieldName, final String childElementName) throws NoSuchElementException {
        return child(injectedFieldName, qn(childElementName));
      }

      public EB array(final String injectedFieldName, final QName childElementName) throws NoSuchElementException {
        return addInjectionSpec(injectedFieldName, childElementName, true, (sourceClass) -> new InjectedElementParser.ArrayInjectionSpec<>(sourceClass, childElementName, false));
      }

      public EB array(final String injectedFieldName, final String childElementName) throws NoSuchElementException {
        return array(injectedFieldName, qn(childElementName));
      }

      public EB list(final String injectedFieldName, final QName childElementName) throws NoSuchElementException {
        return addInjectionSpec(injectedFieldName, childElementName, true, (sourceClass) -> new InjectedElementParser.ListInjectionSpec<>(sourceClass, childElementName, false));
      }

      public EB list(final String injectedFieldName, final String childElementName) throws NoSuchElementException {
        return list(injectedFieldName, qn(childElementName));
      }

      public EB set(final String injectedFieldName, final QName childElementName) throws NoSuchElementException {
        return addInjectionSpec(injectedFieldName, childElementName, true, (sourceClass) -> new InjectedElementParser.SetInjectionSpec<>(sourceClass, childElementName, false));
      }

      public EB set(final String injectedFieldName, final String childElementName) throws NoSuchElementException {
        return set(injectedFieldName, qn(childElementName));
      }

      public EB saved(final String injectedFieldName, final QName savedElementName) throws NoSuchElementException {
        return addInjectionSpec(injectedFieldName, savedElementName, false, (sourceClass) -> new InjectedElementParser.ObjectInjectionSpec<>(sourceClass, savedElementName, true));
      }

      public EB saved(final String injectedFieldName, final String savedElementName) throws NoSuchElementException {
        return saved(injectedFieldName, qn(savedElementName));
      }

      public EB savedArray(final String injectedFieldName, final QName savedElementName) throws NoSuchElementException {
        return addInjectionSpec(injectedFieldName, savedElementName, false, (sourceClass) -> new InjectedElementParser.ArrayInjectionSpec<>(sourceClass, savedElementName, true));
      }

      public EB savedArray(final String injectedFieldName, final String savedElementName) throws NoSuchElementException {
        return savedArray(injectedFieldName, qn(savedElementName));
      }

      public EB savedList(final String injectedFieldName, final QName savedElementName) throws NoSuchElementException {
        return addInjectionSpec(injectedFieldName, savedElementName, false, (sourceClass) -> new InjectedElementParser.ListInjectionSpec<>(sourceClass, savedElementName, true));
      }

      public EB savedList(final String injectedFieldName, final String savedElementName) throws NoSuchElementException {
        return savedList(injectedFieldName, qn(savedElementName));
      }

      public EB savedSet(final String injectedFieldName, final QName savedElementName) throws NoSuchElementException {
        return addInjectionSpec(injectedFieldName, savedElementName, false, (sourceClass) -> new InjectedElementParser.SetInjectionSpec<>(sourceClass, savedElementName, true));
      }

      public EB savedSet(final String injectedFieldName, final String savedElementName) throws NoSuchElementException {
        return savedSet(injectedFieldName, qn(savedElementName));
      }

      public SB build() {
        return add(new InjectedElementParser<ET>(targetClass, elementName, saveTargetValue, childParsers, injectionSpecs));
      }

    } // SchemaBuilder.InjectedElementBuilder

  } // SchemaBuilder

}
