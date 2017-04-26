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


/**
 * You {@linkplain #buildSchema(Class, URI) build} a set of parsers you wish to capture from an
 * {@linkplain XMLEventReader XML event stream} and this class provides a {@link Stream} implementation which will
 * read/parse/construct them dynamically as they are retrieved.
 *
 * @param <T> The type of target objects to be streamed.
 */
@NonNullByDefault
public class XMLStreamParser<@NonNull T> {
  private static final XMLInputFactory XML_INPUT_FACTORY = XMLInputFactory.newInstance();
  protected final Map<QName,ElementParser<?>> elementParsers;
  private final ElementParser<?> documentParser;
  private final ElementParser<T> targetParser;

  protected XMLStreamParser(final Class<T> targetClass, final Collection<ElementParser<?>> elementParsers, final QName documentElementName, final QName targetElementName) throws NoSuchElementException, ClassCastException {
    Objects.requireNonNull(targetClass, "targetClass");
    this.elementParsers = Collections.unmodifiableMap(new ConcurrentHashMap<>(Objects.requireNonNull(elementParsers, "null elementParsers").stream().collect(Collectors.<ElementParser<?>,QName,ElementParser<?>> toMap(ElementParser::getElementName, Function.identity()))));
    this.documentParser = Optional.ofNullable(this.elementParsers.get(Objects.requireNonNull(documentElementName, "null documentElementName"))).orElseThrow(() -> new NoSuchElementException("No parser supplied for document element " + documentElementName));
    final ElementParser<?> tp1 = Optional.ofNullable(this.elementParsers.get(Objects.requireNonNull(targetElementName, "null targetElementName"))).orElseThrow(() -> new NoSuchElementException("No parser supplied for target element " + targetElementName));
    if (!targetClass.isAssignableFrom(tp1.getTargetClass())) throw new ClassCastException("Specified target parser results ('" + tp1.getTargetClass() + "') are incompatible with target class ('" + targetClass + "')");
    @SuppressWarnings("unchecked")
    final ElementParser<T> tp2 = (ElementParser<T>)tp1;
    this.targetParser = tp2;
    return;
  }

  public Stream<T> parse(final InputStream inputStream) throws XMLStreamException {
    final XMLEventReader reader = XML_INPUT_FACTORY.createXMLEventReader(inputStream);

    ElementParser<?>.ParsingContextImpl targetParentContext = null;
    try {
      documentParser.parse(null, reader.nextTag(), reader, targetParser); // Read in events up until an element using the targetParser is encountered.
    } catch (TerminatingParserException tpe) {
      targetParentContext = tpe.getParsingContext();
    }

    return (targetParentContext != null) ? StreamSupport.stream(new TargetSpliterator(targetParentContext, reader), false) : Stream.empty();
  }

  /**
   * Read in and discard all subsequent events for the given start element, up to and including it's end element.
   * 
   * @param element The element to skip over.
   * @param reader The source of events.
   * @throws XMLStreamException If there was a problem skipping this element.
   */
  private static final void skip(final StartElement element, final XMLEventReader reader) throws XMLStreamException {
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
    return;
  }

  private static final void ignoreEvent(final XMLEvent event, final XMLEventReader reader) throws XMLStreamException {
    if (event.isStartElement()) { // OK, swallow all the content for this...
      skip(event.asStartElement(), reader);
    } // Other element types don't have children, so we don't have to do anything else to ignore them in their entirety.
    return;
  }

  @SuppressWarnings("unchecked")
  public static <@NonNull T> SchemaBuilder<T,? extends SchemaBuilder<T,?>> buildSchema(final Class<T> targetClass, final @Nullable URI namespace) {
    return new SchemaBuilder<T,SchemaBuilder<T,?>>(targetClass, (Class<SchemaBuilder<T,?>>)(Object)SchemaBuilder.class, namespace, null);
  }

  @SuppressWarnings("unchecked")
  protected static final <@NonNull T> ElementParser<T>.ParsingContextImpl cast(final ElementParsingContext<T> context) {
    return (ElementParser<T>.ParsingContextImpl)(Object)context;
  }

  /**
   * An implementation of this interface is the sole parameter to a {@link Function} you supply for translating element
   * content into target objects.
   */
  public interface ElementParsingContext<@NonNull T> {

    public Class<T> getElementType();

    public QName getElementName();

    public StartElement getStartElement();

    public Deque<StartElement> getElementContext();

    public <@NonNull S> Optional<S> getSavedValueOpt(final QName name, final Class<S> valueClass);

    public default <@NonNull S> Optional<S> getSavedValueOpt(final String localName, final Class<S> valueClass) {
      return getSavedValueOpt(new QName(getElementName().getNamespaceURI(), localName), valueClass);
    }

    public default <@NonNull S> S getSavedValue(final QName name, final Class<S> valueClass) {
      return getSavedValueOpt(name, valueClass).orElseThrow(() -> new NoSuchElementException(name.toString()));
    }

    public default <@NonNull S> S getSavedValue(final String localName, final Class<S> valueClass) {
      return getSavedValue(new QName(getElementName().getNamespaceURI(), localName), valueClass);
    }

    public <@NonNull ET> Stream<ET> getChildValues(final QName name, final Class<ET> valueClass);

    public default <@NonNull ET> Stream<ET> getChildValues(final String localName, final Class<ET> valueClass) {
      return getChildValues(new QName(getElementName().getNamespaceURI(), localName), valueClass);
    }

    public default <@NonNull ET> Optional<ET> getFirstChildValueOpt(final QName name, final Class<ET> valueClass) {
      return getChildValues(name, valueClass).findFirst();
    }

    public default <@NonNull ET> Optional<ET> getFirstChildValueOpt(final String localName, final Class<ET> valueClass) {
      return getFirstChildValueOpt(new QName(getElementName().getNamespaceURI(), localName), valueClass);
    }

    public default <@NonNull ET> ET getFirstChildValue(final QName name, final Class<ET> valueClass) {
      return getFirstChildValueOpt(name, valueClass).orElseThrow(() -> new NoSuchElementException(name.toString()));
    }

    public default <@NonNull ET> ET getFirstChildValue(final String localName, final Class<ET> valueClass) {
      return getFirstChildValue(new QName(getElementName().getNamespaceURI(), localName), valueClass);
    }

  } // ElementParsingContext

  private final class TargetSpliterator implements Spliterator<T> {
    private final int CHARACTERISTICS = 0 | Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.NONNULL | Spliterator.IMMUTABLE;
    private final ElementParser<?>.ParsingContextImpl parentContext;
    private final XMLEventReader reader;

    public TargetSpliterator(final ElementParser<?>.ParsingContextImpl parentContext, final XMLEventReader reader) throws IllegalArgumentException {
      if (!((ElementParser<?>)parentContext.getParser()).getChildParsers().contains(targetParser)) throw new IllegalStateException("Current parser not parent of target parser");
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
    public boolean tryAdvance(final Consumer<? super T> action) {
      try {
        XMLEvent targetEvent = null;
        while ((reader.hasNext()) && (targetEvent == null)) {
          final XMLEvent event = reader.nextEvent();

          if ((event.isEndElement()) || (event.isEndDocument())) { // If this isn't the start of something new, and is the end of the parent element hosting our targetParser's elements, then we're done!
            reader.close(); // Clean up after ourselves.
            return false;
          }

          final Optional<ContentParser<?,?>> parser = ((ElementParser<?>)parentContext.getParser()).findChildParserFor(event);
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
        throw new RuntimeException(xmlse);
      }
    }

  } // TargetSpliterator

  /**
   * This exception is thrown to abort
   * {@linkplain XMLStreamParser.ContentParser#parse(XMLStreamParser.ElementParser.ParsingContextImpl, XMLEvent, XMLEventReader, ElementParser)
   * parsing} and return the current {@linkplain #getParsingContext() parsing context} when the specified
   * {@linkplain XMLStreamParser.ElementParser element} encountered.
   */
  private static class TerminatingParserException extends RuntimeException {
    protected final ElementParser<?>.ParsingContextImpl context;

    public TerminatingParserException(final ElementParser<?>.ParsingContextImpl context) {
      this.context = context;
      return;
    }

    public ElementParser<?>.ParsingContextImpl getParsingContext() {
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
     * @throws TerminatingParserException If an element using the terminatingParser was encountered.
     * @throws XMLStreamException If there was problem processing the event.
     */
    protected abstract T parse(final ElementParser<?>.@Nullable ParsingContextImpl parentContext, final XMLEvent event, final XMLEventReader reader, final @Nullable ElementParser<?> terminatingParser) throws TerminatingParserException, XMLStreamException;

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
    protected final String parse(final ElementParser<?>.@Nullable ParsingContextImpl parentContext, final XMLEvent event, final XMLEventReader reader, final @Nullable ElementParser<?> terminatingParser) throws XMLStreamException {
      return event.asCharacters().getData();
    }

  } // CharactersParser

  protected static class ElementParser<@NonNull T> extends ContentParser<StartElement,T> {
    @SuppressWarnings("unchecked")
    private static final Class<ElementParser<?>> WILDCARD_CLASS = (Class<ElementParser<?>>)(Object)ElementParser.class;
    protected final QName elementName;
    private final Function<ElementParsingContext<T>,T> targetFunction;
    protected final boolean saveTargetValue;
    private final Set<ContentParser<?,?>> childParsers;

    public ElementParser(final Class<T> targetClass, final QName elementName, final Function<ElementParsingContext<T>,T> targetFunction, final boolean saveTargetValue, final @NonNull ContentParser<?,?>... childParsers) {
      super(StartElement.class, targetClass);
      this.elementName = elementName;
      this.targetFunction = targetFunction;
      this.saveTargetValue = saveTargetValue;
      this.childParsers = (childParsers != null) ? Collections.unmodifiableSet(new CopyOnWriteArraySet<>(Arrays.asList(childParsers))) : Collections.emptySet();
      return;
    }

    public final QName getElementName() {
      return elementName;
    }

    public final Set<ContentParser<?,?>> getChildParsers() {
      return childParsers;
    }

    @Override
    protected final boolean isParserFor(final XMLEvent event) {
      if (!super.isParserFor(event)) return false;
      return elementName.equals(event.asStartElement().getName());
    }

    protected final Optional<ContentParser<?,?>> findChildParserFor(final @Nullable XMLEvent event) {
      return (event != null) ? childParsers.stream().filter((parser) -> parser.isParserFor(event)).findFirst() : Optional.empty();
    }

    @Override
    protected final T parse(final ElementParser<?>.@Nullable ParsingContextImpl parentContext, final XMLEvent event, final XMLEventReader reader, final @Nullable ElementParser<?> terminatingParser) throws TerminatingParserException, XMLStreamException {
      final ParsingContextImpl context = (parentContext != null) ? new ParsingContextImpl(parentContext, eventClass.cast(event)) : new ParsingContextImpl(eventClass.cast(event));
      context.parseChildren(reader, terminatingParser);
      final T value = targetFunction.apply(context);
      if (saveTargetValue) context.saveValue(value);
      return value;
    }

    @Override
    public String toString() {
      return '<' + elementName.toString() + '>';
    }

    @SuppressWarnings("unchecked")
    private static final <@NonNull ET> Stream<Map.Entry<ElementParser<ET>,ET>> getElements(final Stream<? extends Map.Entry<? extends ContentParser<?,?>,?>> values, final @Nullable QName name, final Class<ET> valueClass) {
      return values.filter((entry) -> ElementParser.class.isInstance(entry.getKey())).<Map.Entry<ElementParser<?>,?>> map((entry) -> new AbstractMap.SimpleImmutableEntry<>(ElementParser.class.cast(entry.getKey()), entry.getValue())).filter((entry) -> (name == null) || name.equals(entry.getKey().getElementName())).filter((entry) -> valueClass.isAssignableFrom(entry.getKey().getTargetClass())).<Map.Entry<ElementParser<ET>,ET>> map((entry) -> new AbstractMap.SimpleImmutableEntry<>((ElementParser<ET>)entry.getKey(), valueClass.cast(entry.getValue())));
    }

    public final class ParsingContextImpl implements ElementParsingContext<T> {
      private final Map<ElementParser<?>,Object> savedValues; // Reference a single object, shared by the entire context tree.
      private final List<Map.Entry<ContentParser<?,?>,?>> childValues = new ArrayList<>();
      private final ElementParser<?>.@Nullable ParsingContextImpl parentContext;
      private final StartElement startElement;

      public ParsingContextImpl(final StartElement startElement) {
        savedValues = new ConcurrentHashMap<ElementParser<?>,Object>();
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
      public Class<T> getElementType() {
        return targetClass;
      }

      @Override
      public final QName getElementName() {
        return elementName;
      }

      @Override
      public StartElement getStartElement() {
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
      public Deque<StartElement> getElementContext() {
        return getElementContextImpl(new ArrayDeque<>(getDepth() + 1));
      }

      private XMLEvent getNextEvent(final XMLEventReader reader, final @Nullable ElementParser<?> terminatingParser) throws TerminatingParserException, XMLStreamException {
        if ((terminatingParser != null) && (findChildParserFor(reader.peek()).equals(Optional.of(terminatingParser)))) throw new TerminatingParserException(this);
        return reader.nextEvent();
      }

      protected void parseChildren(final XMLEventReader reader, final @Nullable ElementParser<?> terminatingParser) throws TerminatingParserException, XMLStreamException {
        XMLEvent event = getNextEvent(reader, terminatingParser);
        while (!event.isEndElement()) {
          final Optional<ContentParser<?,?>> parser = findChildParserFor(event);
          if (parser.isPresent()) {
            final Object child = parser.get().parse(this, parser.get().eventClass.cast(event), reader, terminatingParser);
            childValues.add(new AbstractMap.SimpleImmutableEntry<ContentParser<? extends XMLEvent,?>,Object>(parser.get(), child));
          } else { // Ignore any content the user didn't specify a parser for...
            ignoreEvent(event, reader);
          }
          event = getNextEvent(reader, terminatingParser);
        }
        return;
      }

      public Optional<T> saveValue(final T value) {
        final Object oldValue = savedValues.put(ElementParser.this, value);
        return (oldValue != null) ? Optional.of(ElementParser.this.targetClass.cast(oldValue)) : Optional.empty();
      }

      public <@NonNull S> Optional<S> getSavedValueOpt(final ElementParser<S> parser) {
        final Object value = savedValues.get(parser);
        return (value != null) ? Optional.of(parser.targetClass.cast(value)) : Optional.empty();
      }

      public <@NonNull S> S getSavedValue(final ElementParser<S> parser) throws NoSuchElementException {
        return getSavedValueOpt(parser).orElseThrow(() -> new NoSuchElementException(ElementParser.this.toString()));
      }

      public <@NonNull ET> Optional<ET> getFirstChildValueOpt(final ContentParser<?,ET> parser) throws NoSuchElementException {
        return getChildValues(parser).findFirst();
      }

      public <@NonNull ET> ET getFirstChildValue(final ContentParser<?,ET> parser) throws NoSuchElementException {
        return getFirstChildValueOpt(parser).orElseThrow(() -> new NoSuchElementException(parser.toString()));
      }

      @Override
      public <@NonNull S> Optional<S> getSavedValueOpt(final QName name, final Class<S> valueClass) {
        return getElements(savedValues.entrySet().stream(), name, valueClass).map(Map.Entry::getValue).findAny();
      }

      public <@NonNull ET> Stream<ET> getChildValues(final ContentParser<?,ET> parser) {
        return childValues.stream().filter((entry) -> entry.getKey() == parser).map(Map.Entry::getValue).map((v) -> parser.getTargetClass().cast(v));
      }

      @Override
      public <@NonNull ET> Stream<ET> getChildValues(final QName name, final Class<ET> valueClass) {
        return getElements(childValues.stream(), name, valueClass).map(Map.Entry::getValue);
      }

    } // ElementParser.InvocationContext

  } // ElementParser

  protected static class ContainerElementParser extends ElementParser<StartElement> {

    public ContainerElementParser(final QName name, final @NonNull ContentParser<?,?>... childParsers) {
      super(StartElement.class, name, (context) -> context.getStartElement(), false, childParsers);
      return;
    }

  } // ContainerElementParser

  protected static class WrapperElementParser<@NonNull T> extends ElementParser<T> {

    public WrapperElementParser(final QName name, final ElementParser<T> wrappedElement) {
      super(wrappedElement.targetClass, name, (context) -> cast(context).getFirstChildValue(wrappedElement), false, wrappedElement);
      return;
    }

  } // WrapperElementParser

  protected static class TextElementParser<@NonNull T> extends ElementParser<T> {
    private static final CharactersParser CHARACTERS_PARSER = new CharactersParser(true, true, false);

    public TextElementParser(final Class<T> targetClass, final QName name, final Function<? super String,? extends T> targetFunction, final boolean saveTargetValue) {
      super(targetClass, name, (context) -> targetFunction.apply(cast(context).getChildValues(CHARACTERS_PARSER).collect(Collectors.joining())), saveTargetValue, CHARACTERS_PARSER);
      return;
    }

  } // TextElementParser

  protected static class StringElementParser extends TextElementParser<String> {

    public StringElementParser(final QName name, final boolean saveTargetValue) {
      super(String.class, name, Function.identity(), saveTargetValue);
      return;
    }

  } // StringElementParser

  public static class SchemaBuilder<@NonNull T,@NonNull SB extends SchemaBuilder<?,?>> {
    protected final Class<T> targetClass;
    protected final Class<? extends SB> builderType;
    protected final @Nullable URI namespace;
    protected final Map<QName,ElementParser<?>> elementParsers;

    protected SchemaBuilder(final Class<T> targetClass, final Class<? extends SB> builderType, final @Nullable URI namespace, final @Nullable Map<QName,ElementParser<?>> elementParsers) {
      this.targetClass = Objects.requireNonNull(targetClass);
      this.builderType = Objects.requireNonNull(builderType);
      this.namespace = namespace;
      this.elementParsers = (elementParsers != null) ? new HashMap<>(elementParsers) : new HashMap<>();
      return;
    }

    protected SB forkImpl(final @Nullable URI namespace) {
      return builderType.cast(new SchemaBuilder<T,SB>(targetClass, builderType, namespace, elementParsers));
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

    public final @NonNull QName[] qns(final @NonNull String... localNames) {
      return ((localNames != null) ? Arrays.<String> asList(localNames) : Collections.<String> emptyList()).stream().map(this::qn).toArray((n) -> new @NonNull QName[n]);
    }

    protected SB add(final ElementParser<?> elementParser) {
      if (elementParsers.containsKey(elementParser.getElementName())) throw new IllegalArgumentException("Attempt to add parser '" + elementParser.getElementName() + "' twice");
      elementParsers.put(elementParser.getElementName(), elementParser);
      return builderType.cast(this);
    }

    protected final <E extends ElementParser<?>> E getParser(final Class<E> type, final QName name) throws NoSuchElementException, ClassCastException {
      return type.cast(Optional.ofNullable(elementParsers.get(name)).orElseThrow(() -> new NoSuchElementException("No such parser '" + name + "'")));
    }

    protected final <E extends ElementParser<?>> E getParser(final Class<E> type, final String localName) throws NoSuchElementException, ClassCastException {
      return getParser(type, qn(localName));
    }

    protected final @NonNull ElementParser<?>[] getParsers(final @NonNull QName... names) throws NoSuchElementException, ClassCastException {
      return ((names != null) ? Arrays.<QName> asList(names) : Collections.<QName> emptyList()).stream().<ElementParser<?>> map((qn) -> getParser(ElementParser.WILDCARD_CLASS, qn)).toArray((n) -> new ElementParser<?>[n]);
    }

    protected final @NonNull ElementParser<?>[] getParsers(final @NonNull String... localNames) throws NoSuchElementException, ClassCastException {
      return getParsers(qns(localNames));
    }

    public final <@NonNull ET> SB element(final String localName, final Class<ET> targetClass, final Function<ElementParsingContext<ET>,ET> targetFunction, final boolean saveTargetValue, final @NonNull QName... childElementNames) throws NoSuchElementException, ClassCastException {
      return add(new ElementParser<ET>(targetClass, qn(localName), targetFunction, saveTargetValue, getParsers(childElementNames)));
    }

    public final <@NonNull ET> SB element(final String localName, final Class<ET> targetClass, final Function<ElementParsingContext<ET>,ET> targetFunction, final boolean saveTargetValue, final @NonNull String... childElementNames) throws NoSuchElementException, ClassCastException {
      return element(localName, targetClass, targetFunction, saveTargetValue, qns(childElementNames));
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

    public XMLStreamParser<T> build(final QName documentElementName, final QName targetElementName) throws NoSuchElementException, ClassCastException {
      return new XMLStreamParser<T>(targetClass, elementParsers.values(), documentElementName, targetElementName);
    }

    public XMLStreamParser<T> build(final String documentParserName, final String targetParserName) throws NoSuchElementException, ClassCastException {
      return build(qn(documentParserName), qn(targetParserName));
    }

  } // SchemaBuilder

}
