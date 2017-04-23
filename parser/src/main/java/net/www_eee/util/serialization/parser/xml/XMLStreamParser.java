/*
 * Copyright 2016-2017 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU LESSER GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, a copy of which you should have received in the file LICENSE.txt.
 */

package net.www_eee.util.serialization.parser.xml;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import javax.xml.namespace.*;
import javax.xml.stream.*;
import javax.xml.stream.events.*;

import org.eclipse.jdt.annotation.*;


/**
 * You supply a tree of {@linkplain Parser parser} objects specifying the {@linkplain Element elements} you wish to
 * capture from an {@linkplain XMLEventReader XML event stream} and this class provides a {@link Stream} implementation
 * which will read/parse/construct them dynamically as they are retrieved.
 *
 * @param <T> The type of target objects to be streamed.
 */
@NonNullByDefault
public class XMLStreamParser<@NonNull T> {
  private static final XMLInputFactory XML_INPUT_FACTORY = XMLInputFactory.newInstance();
  private final Element<?> documentParser;
  private final Element<T> targetParser;

  public XMLStreamParser(final Element<?> documentParser, final Element<T> targetParser) {
    this.documentParser = documentParser;
    this.targetParser = targetParser;
    return;
  }

  public Stream<T> parse(final InputStream inputStream) throws XMLStreamException {
    final XMLEventReader reader = XML_INPUT_FACTORY.createXMLEventReader(inputStream);

    State initialState = null;
    try {
      documentParser.parse(new State(), reader.nextTag(), reader, targetParser); // Read in events up until an element using the targetParser is encountered.
    } catch (TerminatingParserException tpe) {
      initialState = tpe.getState();
    }

    return (initialState != null) ? StreamSupport.stream(new TargetSpliterator(initialState, reader), false) : Stream.empty();
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

  public static final <@NonNull T> Stream<T> filter(final List<Map.Entry<Parser<?,?>,?>> children, final Parser<?,T> parser) {
    return children.stream().filter((entry) -> entry.getKey() == parser).map(Map.Entry::getValue).map((v) -> parser.getTargetClass().cast(v));
  }

  public static final <@NonNull T> Optional<T> findFirst(final List<Map.Entry<Parser<?,?>,?>> children, final Parser<?,T> parser) {
    return filter(children, parser).findFirst();
  }

  public static final <@NonNull T> T firstValue(final List<Map.Entry<Parser<?,?>,?>> children, final Parser<?,T> parser) throws NoSuchElementException {
    return findFirst(children, parser).orElseThrow(() -> new NoSuchElementException(parser.toString()));
  }

  public static final class State {
    private final Map<Parser<?,?>,Object> values;
    private final Stack<Map.Entry<Element<?>,StartElement>> context;

    public State() {
      values = new ConcurrentHashMap<Parser<?,?>,Object>();
      context = new Stack<>();
      return;
    }

    public State(final State other) {
      values = new ConcurrentHashMap<Parser<?,?>,Object>(other.values);
      context = new Stack<>();
      context.addAll(other.context);
      return;
    }

    public <@NonNull T> Optional<T> setValue(final Parser<?,T> parser, final T value) {
      final Object oldValue = values.put(parser, value);
      return (oldValue != null) ? Optional.of(parser.targetClass.cast(oldValue)) : Optional.empty();
    }

    public <@NonNull T> Optional<T> getValueOpt(final Parser<?,T> parser) {
      final Object value = values.get(parser);
      return (value != null) ? Optional.of(parser.targetClass.cast(value)) : Optional.empty();
    }

    public <@NonNull T> T getValue(final Parser<?,T> parser) throws NoSuchElementException {
      return getValueOpt(parser).orElseThrow(() -> new NoSuchElementException(parser.toString()));
    }

    public synchronized void pushContext(final Element<?> parser, final StartElement element) {
      context.push(new AbstractMap.SimpleImmutableEntry<>(parser, element));
      return;
    }

    public synchronized void popContext() throws IllegalStateException {
      try {
        context.pop();
      } catch (EmptyStackException ese) {
        throw new IllegalStateException(ese);
      }
      return;
    }

    public synchronized Element<?> currentParser() throws IllegalStateException {
      return Optional.ofNullable((!context.isEmpty()) ? context.peek().getKey() : null).orElseThrow(() -> new IllegalStateException("No current parser"));
    }

    public synchronized StartElement currentElement() throws IllegalStateException {
      return Optional.ofNullable((!context.isEmpty()) ? context.peek().getValue() : null).orElseThrow(() -> new IllegalStateException("No current element"));
    }

    public synchronized Optional<StartElement> getElement(final Element<?> parser) {
      for (Map.Entry<Element<?>,StartElement> c : context) {
        if (c.getKey() == parser) return Optional.of(c.getValue());
      }
      return Optional.empty();
    }

  } // State

  private final class TargetSpliterator implements Spliterator<T> {
    private final int CHARACTERISTICS = 0 | Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.NONNULL | Spliterator.IMMUTABLE;
    private final State state;
    private final XMLEventReader reader;

    public TargetSpliterator(final State state, final XMLEventReader reader) throws IllegalArgumentException {
      if (!state.currentParser().getChildParsers().contains(targetParser)) throw new IllegalStateException("Current parser not parent of target parser");
      this.state = state;
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

          final Optional<Parser<?,?>> parser = state.currentParser().findChildParserFor(event);
          if (parser.isPresent()) {
            if (parser.get() == targetParser) { // There could be some other content before the next applicable target event.
              targetEvent = event;
            } else { // If they supplied a parser for this, so use it.
              parser.get().parse(state, event, reader, null);
            }
          } else {
            ignoreEvent(event, reader);
          }
        }
        if (targetEvent == null) return false;
        action.accept(targetParser.parse(state, targetEvent, reader, null));
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
   * This exception is thrown to abort {@linkplain XMLStreamParser.Parser#parse(State,XMLEvent,XMLEventReader,Element)
   * parsing} and return the current {@link #getState() State} when the specified {@link XMLStreamParser.Element
   * Element} parser is encountered.
   */
  private static class TerminatingParserException extends RuntimeException {
    protected final State state;

    public TerminatingParserException(final State state) {
      this.state = new State(state); // Copy the state, since the original context will be unwound as this exception propagates.
      return;
    }

    public State getState() {
      return state;
    }

  } // TerminatingParserException

  public static abstract class Parser<E extends XMLEvent,@NonNull T> implements Serializable {
    protected final Class<E> eventClass;
    protected final Class<T> targetClass;
    protected final boolean storeTargetValue;

    protected Parser(final Class<E> eventClass, final Class<T> targetClass, final boolean storeTargetValue) {
      this.eventClass = eventClass;
      this.targetClass = targetClass;
      this.storeTargetValue = storeTargetValue;
      return;
    }

    public Class<E> getEventClass() {
      return eventClass;
    }

    public Class<T> getTargetClass() {
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
     * @param state The state associated with parsing of the current document.
     * @param event The event to be processed.
     * @param reader The reader to read any content from.
     * @param terminatingParser Throw a TerminatingParserException if an element using this parser is encountered.
     * @return The value created from the supplied event and it's content.
     * @throws TerminatingParserException If an element using the terminatingParser was encountered.
     * @throws XMLStreamException If there was problem processing the event.
     */
    protected abstract T parseImpl(final State state, final XMLEvent event, final XMLEventReader reader, final @Nullable Element<?> terminatingParser) throws TerminatingParserException, XMLStreamException;

    protected final T parse(final State state, final XMLEvent event, final XMLEventReader reader, final @Nullable Element<?> terminatingParser) throws TerminatingParserException, XMLStreamException {
      final T value = parseImpl(state, event, reader, terminatingParser);
      if (storeTargetValue) state.setValue(this, value);
      return value;
    }

  } // Parser

  public static class Text extends Parser<Characters,String> {
    private final boolean includeWhiteSpace;
    private final boolean includeCData;
    private final boolean includeIgnorableWhiteSpace;

    public Text(final boolean includeWhiteSpace, final boolean includeCData, final boolean includeIgnorableWhiteSpace) {
      super(Characters.class, String.class, false);
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
    protected final String parseImpl(final State state, final XMLEvent event, final XMLEventReader reader, final @Nullable Element<?> terminatingParser) throws XMLStreamException {
      return event.asCharacters().getData();
    }

  } // Text

  public static class Element<@NonNull T> extends Parser<StartElement,T> {
    protected final QName name;
    private final BiFunction<State,List<Map.Entry<Parser<?,?>,?>>,T> targetFunction;
    private final Set<Parser<?,?>> childParsers;

    public Element(final Class<T> targetClass, final QName name, final BiFunction<State,List<Map.Entry<Parser<?,?>,?>>,T> targetFunction, final boolean storeTargetValue, final Collection<Parser<?,?>> childParsers) {
      super(StartElement.class, targetClass, storeTargetValue);
      this.name = name;
      this.targetFunction = targetFunction;
      this.childParsers = Collections.unmodifiableSet(new CopyOnWriteArraySet<>(childParsers));
      return;
    }

    public Element(final Class<T> targetClass, final QName name, final BiFunction<State,List<Map.Entry<Parser<?,?>,?>>,T> targetFunction, final boolean storeTargetValue, final @NonNull Parser<?,?>... childParsers) {
      this(targetClass, name, targetFunction, storeTargetValue, (childParsers != null) ? Arrays.asList(childParsers) : Collections.emptyList());
      return;
    }

    public final QName getName() {
      return name;
    }

    public Set<Parser<?,?>> getChildParsers() {
      return childParsers;
    }

    @Override
    protected final boolean isParserFor(final XMLEvent event) {
      if (!super.isParserFor(event)) return false;
      return name.equals(event.asStartElement().getName());
    }

    protected final Optional<Parser<?,?>> findChildParserFor(final @Nullable XMLEvent event) {
      return (event != null) ? childParsers.stream().filter((parser) -> parser.isParserFor(event)).findFirst() : Optional.empty();
    }

    private final XMLEvent getNextEvent(final State state, final XMLEventReader reader, final @Nullable Element<?> terminatingParser) throws TerminatingParserException, XMLStreamException {
      if ((terminatingParser != null) && (findChildParserFor(reader.peek()).equals(Optional.of(terminatingParser)))) throw new TerminatingParserException(state);
      return reader.nextEvent();
    }

    private final List<Map.Entry<Parser<?,?>,?>> parseChildren(final State state, final XMLEventReader reader, final @Nullable Element<?> terminatingParser) throws TerminatingParserException, XMLStreamException {
      final List<Map.Entry<Parser<?,?>,?>> children = new ArrayList<>();
      XMLEvent event = getNextEvent(state, reader, terminatingParser);
      while (!event.isEndElement()) {
        final Optional<Parser<?,?>> parser = findChildParserFor(event);
        if (parser.isPresent()) {
          final Object child = parser.get().parse(state, event, reader, terminatingParser);
          children.add(new AbstractMap.SimpleImmutableEntry<Parser<? extends XMLEvent,?>,Object>(parser.get(), child));
        } else { // Ignore any content the user didn't specify a parser for...
          ignoreEvent(event, reader);
        }
        event = getNextEvent(state, reader, terminatingParser);
      }
      return children;
    }

    @Override
    protected final T parseImpl(final State state, final XMLEvent event, final XMLEventReader reader, final @Nullable Element<?> terminatingParser) throws TerminatingParserException, XMLStreamException {
      state.pushContext(this, event.asStartElement());
      try {
        final List<Map.Entry<Parser<?,?>,?>> children = parseChildren(state, reader, terminatingParser);
        return targetFunction.apply(state, children);
      } finally {
        state.popContext();
      }
    }

    @Override
    public String toString() {
      return '<' + name.toString() + '>';
    }

  } // Element

  public static class ContainerElement extends Element<StartElement> {

    public ContainerElement(final QName name, final Collection<Parser<?,?>> childParsers) {
      super(StartElement.class, name, (state, children) -> state.currentElement(), false, childParsers);
      return;
    }

    public ContainerElement(final QName name, final @NonNull Parser<?,?>... childParsers) {
      this(name, (childParsers != null) ? Arrays.asList(childParsers) : Collections.emptyList());
      return;
    }

    public ContainerElement(final QName name, final Parser<?,?> childParser) {
      this(name, Collections.singleton(childParser));
      return;
    }

  } // ContainerElement

  public static class WrapperElement<@NonNull T> extends Element<T> {

    public WrapperElement(final QName name, final Element<T> wrappedElement) {
      super(wrappedElement.targetClass, name, (state, children) -> firstValue(children, wrappedElement), false, wrappedElement);
      return;
    }

  } // WrapperElement

  public static class TextElement<@NonNull T> extends Element<T> {
    private static final Text TEXT_PARSER = new Text(true, true, false);

    public TextElement(final Class<T> targetClass, final QName name, final Function<String,T> targetFunction, final boolean storeTargetValue) {
      super(targetClass, name, (state, children) -> targetFunction.apply(filter(children, TEXT_PARSER).collect(Collectors.joining())), storeTargetValue, Collections.singleton(TEXT_PARSER));
      return;
    }

  } // TextElement

  public static class StringElement extends TextElement<String> {

    public StringElement(final QName name, final boolean storeTargetValue) {
      super(String.class, name, Function.identity(), storeTargetValue);
      return;
    }

  } // StringElement

}
