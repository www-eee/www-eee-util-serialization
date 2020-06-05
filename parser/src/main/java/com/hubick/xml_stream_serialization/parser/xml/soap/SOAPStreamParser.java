/*
 * Copyright 2016-2020 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU AFFERO GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, plus additional permissions, a copy of which you should have
 * received in the file LICENSE.txt.
 */

package com.hubick.xml_stream_serialization.parser.xml.soap;

import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

import javax.xml.namespace.*;
import javax.xml.soap.*;
import javax.xml.ws.soap.*;

import org.eclipse.jdt.annotation.*;

import com.hubick.xml_stream_serialization.parser.xml.*;


/**
 * An {@link XMLStreamParser} with additional support for {@linkplain #buildSOAP12Schema(URI) defining}
 * {@linkplain SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} elements.
 *
 * @param <T> The type of target values to be streamed.
 */
@NonNullByDefault
public class SOAPStreamParser<@NonNull T> extends XMLStreamParser<T> {
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Envelope</code> element.
   */
  public static final QName SOAP_1_2_ENVELOPE_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Envelope");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Header</code> element.
   */
  public static final QName SOAP_1_2_HEADER_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Header");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Body</code> element.
   */
  public static final QName SOAP_1_2_BODY_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Body");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Value</code> element.
   */
  public static final QName SOAP_1_2_VALUE_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Value");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Code</code> element.
   */
  public static final QName SOAP_1_2_CODE_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Code");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Text</code> element.
   */
  public static final QName SOAP_1_2_TEXT_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Text");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Reason</code> element.
   */
  public static final QName SOAP_1_2_REASON_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Reason");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Detail</code> element.
   */
  public static final QName SOAP_1_2_DETAIL_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Detail");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Fault</code> element.
   */
  public static final QName SOAP_1_2_FAULT_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Fault");
  /**
   * The identifier for the JAX-WS namespace.
   */
  public static final String URI_NS_JAX_WS = "http://jax-ws.dev.java.net/";
  /**
   * A {@link QName} constant for the {@link #URI_NS_JAX_WS JAX-WS} <code>message</code> element.
   */
  public static final QName JAX_WS_MESSAGE_QNAME = new QName(URI_NS_JAX_WS, "message");
  /**
   * A {@link QName} constant for the {@link #URI_NS_JAX_WS JAX-WS} <code>cause</code> element.
   */
  public static final QName JAX_WS_CAUSE_QNAME = new QName(URI_NS_JAX_WS, "cause");
  /**
   * A {@link QName} constant for the {@link #URI_NS_JAX_WS JAX-WS} <code>frame</code> element.
   */
  public static final QName JAX_WS_FRAME_QNAME = new QName(URI_NS_JAX_WS, "frame");
  /**
   * A {@link QName} constant for the {@link #URI_NS_JAX_WS JAX-WS} <code>stackTrace</code> element.
   */
  public static final QName JAX_WS_STACK_TRACE_QNAME = new QName(URI_NS_JAX_WS, "stackTrace");
  /**
   * A {@link QName} constant for the {@link #URI_NS_JAX_WS JAX-WS} <code>exception</code> element.
   */
  public static final QName JAX_WS_EXCEPTION_QNAME = new QName(URI_NS_JAX_WS, "exception");
  protected static final SOAPFactory SOAP_FACTORY;
  static {
    try {
      SOAP_FACTORY = SOAPFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL);
    } catch (SOAPException se) {
      throw new RuntimeException(se);
    }
  }
  protected static final SimpleElementParser<QName> SOAP_1_2_VALUE_ELEMENT_PARSER = new SimpleElementParser<>(QName.class, SOAP_1_2_VALUE_QNAME, (s) -> new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, s.substring(s.indexOf(':') + 1), s.substring(0, s.indexOf(':'))), false);
  private static final WrapperElementParser<QName> SOAP_1_2_CODE_ELEMENT_PARSER = new WrapperElementParser<>(SOAP_1_2_CODE_QNAME, null, false, SOAP_1_2_VALUE_ELEMENT_PARSER);
  protected static final StringElementParser SOAP_1_2_TEXT_ELEMENT_PARSER = new StringElementParser(SOAP_1_2_TEXT_QNAME, false);
  private static final WrapperElementParser<String> SOAP_1_2_REASON_ELEMENT_PARSER = new WrapperElementParser<>(SOAP_1_2_REASON_QNAME, null, false, SOAP_1_2_TEXT_ELEMENT_PARSER);
  protected static final StringElementParser JAX_WS_MESSAGE_ELEMENT_PARSER = new StringElementParser(JAX_WS_MESSAGE_QNAME, false);
  protected static final ElementParser<StackTraceElement> JAX_WS_FRAME_ELEMENT_PARSER = new ElementParser<>(StackTraceElement.class, JAX_WS_FRAME_QNAME, (ctx) -> new StackTraceElement(ctx.getRequiredAttr("class"), ctx.getRequiredAttr("method"), ctx.getAttrOrNull("file"), ctx.getOptionalAttr("line", Integer::valueOf).orElse(-1)), false);
  protected static final ElementParser<StackTraceElement[]> JAX_WS_STACK_TRACE_ELEMENT_PARSER = new ElementParser<>(StackTraceElement[].class, JAX_WS_STACK_TRACE_QNAME, (ctx) -> ctx.getChildValues(JAX_WS_FRAME_QNAME, StackTraceElement.class).toArray(StackTraceElement[]::new), false, null, false, JAX_WS_FRAME_ELEMENT_PARSER);
  protected static final ElementParser<Throwable> JAX_WS_CAUSE_ELEMENT_PARSER = new ElementParser<>(Throwable.class, JAX_WS_CAUSE_QNAME, (ctx) -> createThrowable(ctx.getRequiredAttr("class"), ctx.getChildValueOrNull(JAX_WS_MESSAGE_QNAME, String.class), ctx.getChildValueOrNull(JAX_WS_STACK_TRACE_QNAME, StackTraceElement[].class), ctx.getChildValueOrNull(JAX_WS_CAUSE_QNAME, Throwable.class)), false, null, true, JAX_WS_MESSAGE_ELEMENT_PARSER, JAX_WS_STACK_TRACE_ELEMENT_PARSER);
  protected static final ElementParser<Throwable> JAX_WS_EXCEPTION_ELEMENT_PARSER = new ElementParser<>(Throwable.class, JAX_WS_EXCEPTION_QNAME, (ctx) -> createThrowable(ctx.getRequiredAttr("class"), ctx.getChildValueOrNull(JAX_WS_MESSAGE_QNAME, String.class), ctx.getChildValueOrNull(JAX_WS_STACK_TRACE_QNAME, StackTraceElement[].class), ctx.getChildValueOrNull(JAX_WS_CAUSE_QNAME, Throwable.class)), false, null, true, JAX_WS_MESSAGE_ELEMENT_PARSER, JAX_WS_STACK_TRACE_ELEMENT_PARSER, JAX_WS_CAUSE_ELEMENT_PARSER);
  private static final WrapperElementParser<Throwable> SOAP_1_2_DETAIL_ELEMENT_PARSER = new WrapperElementParser<>(SOAP_1_2_DETAIL_QNAME, null, false, JAX_WS_EXCEPTION_ELEMENT_PARSER);
  protected static final ElementParser<SOAPFaultException> SOAP_1_2_FAULT_ELEMENT_PARSER = new ElementParser<>(SOAPFaultException.class, SOAP_1_2_FAULT_QNAME, (ctx) -> {
    final SOAPFault fault;
    try {
      fault = SOAP_FACTORY.createFault(cast(ctx).getRequiredChildValue(SOAP_1_2_REASON_ELEMENT_PARSER), cast(ctx).getRequiredChildValue(SOAP_1_2_CODE_ELEMENT_PARSER));
    } catch (SOAPException soape) {
      throw ctx.createElementValueException(soape);
    }
    final SOAPFaultException exception = new SOAPFaultException(fault);
    ctx.getOptionalChildValue(SOAP_1_2_DETAIL_QNAME, Throwable.class).ifPresent((cause) -> exception.initCause(cause));
    return exception;
  }, false, null, false, SOAP_1_2_CODE_ELEMENT_PARSER, SOAP_1_2_REASON_ELEMENT_PARSER, SOAP_1_2_DETAIL_ELEMENT_PARSER);

  @SafeVarargs
  protected SOAPStreamParser(final Class<T> targetValueClass, final Set<EnvelopeElementParser> envelopeParsers, final ContainerElementParser targetContainerElementParser, final @NonNull ElementParser<? extends T>... targetValueParsers) {
    super(targetValueClass, envelopeParsers, targetContainerElementParser, targetValueParsers);
    return;
  }

  /**
   * Get a {@link Constructor} for the specified class which has the supplied parameter types, catching any
   * {@link NoSuchMethodException}.
   */
  protected static final <T> Optional<Constructor<T>> getConstructorOptional(final Class<T> resultClass, final Class<?>... parameterTypes) throws SecurityException {
    try {
      return Optional.of(resultClass.getConstructor(parameterTypes));
    } catch (NoSuchMethodException nsme) {
      return Optional.empty();
    }
  }

  /**
   * Get a {@link Constructor} for the specified {@link Throwable} class which accepts both
   * {@linkplain Throwable#getMessage() message} and {@linkplain Throwable#getCause() cause} parameters.
   */
  private static final <T extends Throwable> Optional<Constructor<T>> findMessageAndCauseConstructorForThrowableClass(final Class<T> resultClass, final @Nullable Class<? extends Throwable> causeClass) throws NoSuchMethodException, SecurityException {
    final Optional<Constructor<T>> messageAndCauseConstructor = getConstructorOptional(resultClass, String.class, causeClass);
    if (messageAndCauseConstructor.isPresent()) return messageAndCauseConstructor;
    if (!Throwable.class.equals(causeClass)) return findMessageAndCauseConstructorForThrowableClass(resultClass, causeClass.getSuperclass().asSubclass(Throwable.class)); // Is there a constructor which accepts cause's parent class? Recurse our way up to Throwable.
    return Optional.empty();
  }

  /**
   * Get a {@link Constructor} for the specified {@link Throwable} class which accepts a
   * {@linkplain Throwable#getCause() cause} parameter.
   */
  private static final <T extends Throwable> Optional<Constructor<T>> findCauseConstructorForThrowableClass(final Class<T> resultClass, final @Nullable Class<? extends Throwable> causeClass) throws NoSuchMethodException, SecurityException {
    final Optional<Constructor<T>> causeConstructor = getConstructorOptional(resultClass, causeClass);
    if (causeConstructor.isPresent()) return causeConstructor;
    if (!Throwable.class.equals(causeClass)) return findCauseConstructorForThrowableClass(resultClass, causeClass.getSuperclass().asSubclass(Throwable.class)); // Is there a constructor which accepts cause's parent class? Recurse our way up to Throwable.
    return Optional.empty();
  }

  /**
   * Search for a {@link Constructor} for the specified {@link Throwable} class and use it to create a
   * {@linkplain Constructor#newInstance(Object...) new instance}.
   */
  private static final <T extends Throwable> T findConstructorForThrowableClassAndCreateInstance(final Class<T> throwableClass, final @Nullable String message, final @Nullable Throwable cause) throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {
    T result;
    if ((message != null) && (cause != null)) {
      final Optional<Constructor<T>> messageAndCauseConstructor = findMessageAndCauseConstructorForThrowableClass(throwableClass, cause.getClass());
      if (messageAndCauseConstructor.isPresent()) {
        result = messageAndCauseConstructor.get().newInstance(message, cause);
      } else {
        result = throwableClass.getConstructor(String.class).newInstance(message);
        result.initCause(cause);
      }
    } else if (message != null) {
      result = throwableClass.getConstructor(String.class).newInstance(message);
    } else if (cause != null) {
      final Optional<Constructor<T>> causeConstructor = findCauseConstructorForThrowableClass(throwableClass, Throwable.class);
      if (causeConstructor.isPresent()) {
        result = causeConstructor.get().newInstance(cause);
      } else {
        result = throwableClass.getConstructor().newInstance();
        result.initCause(cause);
      }
    } else {
      result = throwableClass.getConstructor().newInstance();
    }
    return result;
  }

  protected static final Throwable createThrowable(final String className, final @Nullable String message, final StackTraceElement @Nullable [] stackTrace, final @Nullable Throwable cause) {
    Throwable result;
    try {
      final Class<? extends Throwable> resultClass = Class.forName(className).asSubclass(Throwable.class);
      result = findConstructorForThrowableClassAndCreateInstance(resultClass, message, cause); // I don't see any harm in giving this a reasonable try.
    } catch (Throwable err) { // This will trap ClassNotFoundError's if we try to create server side classes which don't exist in this JVM, as well as catch any NoSuchMethodException's if we can't find a constructor we can figure out how to invoke on it.
      result = new Throwable(className + (message != null ? ": " + message : "")); // Fall back to creating a generic Throwable instance, with the server side exception named in the message.
      if (cause != null) result.initCause(cause);
    }
    if (stackTrace != null) result.setStackTrace(stackTrace);
    return result;
  }

  /**
   * Create a SOAP {@link SchemaBuilder SchemaBuilder} which can then be used to define the elements used within the XML
   * documents you wish to {@link SchemaBuilder#createSOAPParser(Class, QName, QName[]) create a parser} for.
   * 
   * @param namespace The (optional) namespace used by your XML.
   * @return A new {@link SchemaBuilder}.
   */
  @SuppressWarnings("unchecked")
  public static SchemaBuilder<@NonNull ? extends SchemaBuilder<@NonNull ?>> buildSOAP12Schema(final @Nullable URI namespace) {
    return new SchemaBuilder<>((Class<SchemaBuilder<?>>)(Object)SchemaBuilder.class, namespace, null, null, false);
  }

  protected static class HeaderElementParser extends ContainerElementParser {

    public HeaderElementParser(final QName elementName, final @Nullable Set<? extends ElementParser<? extends Exception>> childExceptionParsers, final @NonNull ElementParser<?> @Nullable... childValueParsers) {
      super(elementName, childExceptionParsers, false, childValueParsers);
      return;
    }

  } // HeaderElementParser

  protected static class BodyElementParser extends ContainerElementParser {

    public BodyElementParser(final QName elementName, final @Nullable Set<? extends ElementParser<? extends Exception>> childExceptionParsers, final @NonNull ElementParser<?> @Nullable... childValueParsers) {
      super(elementName, childExceptionParsers, false, childValueParsers);
      return;
    }

  } // BodyElementParser

  protected static class EnvelopeElementParser extends ContainerElementParser {

    public EnvelopeElementParser(final QName elementName, final HeaderElementParser headerParser, final BodyElementParser bodyParser) {
      super(elementName, null, false, headerParser, bodyParser);
      return;
    }

    public EnvelopeElementParser(final QName elementName, final BodyElementParser bodyParser) {
      super(elementName, null, false, bodyParser);
      return;
    }

  } // EnvelopeElementParser

  /**
   * An extension of the regular {@link com.hubick.xml_stream_serialization.parser.xml.XMLStreamParser.SchemaBuilder
   * SchemaBuilder} class, adding in additional support for the definition of SOAP
   * {@link #defineHeaderElementWithChildBuilder() Header}, {@link #defineBodyElement(Class, QName) Body}, and
   * {@link #defineEnvelopeElement(boolean) Envelope} elements.
   * 
   * @param <SB> The concrete class of schema builder being used.
   * @see com.hubick.xml_stream_serialization.parser.xml.XMLStreamParser.SchemaBuilder
   */
  public static class SchemaBuilder<@NonNull SB extends SchemaBuilder<@NonNull ?>> extends XMLStreamParser.SchemaBuilder<SB> {

    protected SchemaBuilder(final Class<? extends SB> builderType, final @Nullable URI namespace, final @Nullable Set<ElementParser<?>> elementParsers, final @Nullable Map<String,Function<ElementParsingContext,@Nullable Object>> globalInjectionSpecs, final boolean unmodifiable) {
      super(builderType, namespace, elementParsers, globalInjectionSpecs, unmodifiable);
      this.elementParsers.add(SOAP_1_2_FAULT_ELEMENT_PARSER);
      return;
    }

    @Override
    protected SB forkImpl(final @Nullable URI namespace, final boolean unmodifiable) {
      return Objects.requireNonNull(schemaBuilderType.cast(new SchemaBuilder<SB>(schemaBuilderType, namespace, elementParsers, globalInjectionSpecs, unmodifiable)));
    }

    /**
     * Define a <code>Header</code> element (a specialized {@linkplain #defineContainerElementWithChildBuilder(String)
     * container}).
     * 
     * @return A
     * {@link com.hubick.xml_stream_serialization.parser.xml.XMLStreamParser.SchemaBuilder.ChildElementListBuilder
     * ChildElementListBuilder} which you can use to define which elements the <code>Header</code> will have as
     * children.
     */
    public final ChildElementListBuilder defineHeaderElementWithChildBuilder() {
      return new ChildElementListBuilder(Collections.singleton(SOAP_1_2_FAULT_ELEMENT_PARSER), null) {

        @Override
        public SB completeDefinition() {
          addParser(new HeaderElementParser(SOAP_1_2_HEADER_QNAME, childExceptionParsers, (!childValueParsers.isEmpty()) ? childValueParsers.stream().toArray((n) -> new ElementParser<?>[n]) : null));
          return Objects.requireNonNull(schemaBuilderType.cast(SchemaBuilder.this));
        }

      };
    }

    /**
     * Define a <code>Body</code> element (a specialized {@linkplain #defineContainerElementWithChildBuilder(String)
     * container}).
     * 
     * @param <CT> The type of target value which will be constructed when the child element is parsed.
     * @param childElementTargetValueClass The target value type produced by the element you wish to add as a child of
     * this one.
     * @param childElementName The name of an existing element you wish to add as a child of this one.
     * @return The {@link SOAPStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
     */
    public final <@NonNull CT> SB defineBodyElement(final Class<CT> childElementTargetValueClass, final QName childElementName) throws NoSuchElementException {
      return addParser(new BodyElementParser(SOAP_1_2_BODY_QNAME, Collections.singleton(SOAP_1_2_FAULT_ELEMENT_PARSER), getParserWithTargetType(childElementTargetValueClass, childElementName)));
    }

    /**
     * Define a <code>Body</code> element (a specialized {@linkplain #defineContainerElementWithChildBuilder(String)
     * container}).
     * 
     * @param childElementName The name of an existing element you wish to add as a child of this one.
     * @return The {@link SOAPStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
     */
    public final SB defineBodyElement(final QName childElementName) throws NoSuchElementException {
      return addParser(new BodyElementParser(SOAP_1_2_BODY_QNAME, Collections.singleton(SOAP_1_2_FAULT_ELEMENT_PARSER), getParser(childElementName)));
    }

    /**
     * Define a <code>Body</code> element (a specialized {@linkplain #defineContainerElementWithChildBuilder(String)
     * container}).
     * 
     * @param childElementLocalName The {@linkplain QName#getLocalPart() local name} of an existing element you wish to
     * add as a child of this one (the {@linkplain #getNamespace() current namespace} will be used).
     * @return The {@link SOAPStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @throws NoSuchElementException If the referenced element hasn't been defined in this schema.
     */
    public final SB defineBodyElement(final String childElementLocalName) throws NoSuchElementException {
      return defineBodyElement(qn(childElementLocalName));
    }

    /**
     * Define a <code>Body</code> element (a specialized {@linkplain #defineContainerElementWithChildBuilder(String)
     * container}).
     * 
     * @return A
     * {@link com.hubick.xml_stream_serialization.parser.xml.XMLStreamParser.SchemaBuilder.ChildElementListBuilder
     * ChildElementListBuilder} which you can use to define which elements the <code>Body</code> will have as children.
     */
    public final ChildElementListBuilder defineBodyElementWithChildBuilder() {
      return new ChildElementListBuilder(Collections.singleton(SOAP_1_2_FAULT_ELEMENT_PARSER), null) {

        @Override
        public SB completeDefinition() {
          addParser(new BodyElementParser(SOAP_1_2_BODY_QNAME, childExceptionParsers, (!childValueParsers.isEmpty()) ? childValueParsers.stream().toArray((n) -> new ElementParser<?>[n]) : null));
          return Objects.requireNonNull(schemaBuilderType.cast(SchemaBuilder.this));
        }

      };
    }

    /**
     * Define an <code>Envelope</code> element (a specialized
     * {@linkplain #defineContainerElementWithChildBuilder(String) container}).
     * 
     * @param hasHeader Should the <code>Envelope</code> element being defined reference an existing <code>Header</code>
     * element as it's child?
     * @return The {@link SOAPStreamParser.SchemaBuilder SchemaBuilder} this method was invoked on.
     * @throws NoSuchElementException If the referenced <code>Header</code> element hasn't been defined in this schema.
     */
    public final SB defineEnvelopeElement(final boolean hasHeader) throws NoSuchElementException {
      return addParser(hasHeader ? new EnvelopeElementParser(SOAP_1_2_ENVELOPE_QNAME, getParserOfParserType(HeaderElementParser.class, SOAP_1_2_HEADER_QNAME), getParserOfParserType(BodyElementParser.class, SOAP_1_2_BODY_QNAME)) : new EnvelopeElementParser(SOAP_1_2_ENVELOPE_QNAME, getParserOfParserType(BodyElementParser.class, SOAP_1_2_BODY_QNAME)));
    }

    @Override
    public <@NonNull T> SOAPStreamParser<T> createXMLParser(final Class<T> targetValueClass, final Set<QName> documentElementNames, final QName targetContainerElementName, final @NonNull QName... targetValueElementNames) throws NoSuchElementException {
      return new SOAPStreamParser<T>(targetValueClass, documentElementNames.stream().map((documentElementName) -> getParserOfParserType(EnvelopeElementParser.class, documentElementName)).collect(Collectors.toSet()), getParserOfParserType(ContainerElementParser.class, targetContainerElementName), getParsersWithTargetType(targetValueClass, targetValueElementNames));
    }

    /**
     * Create a {@link SOAPStreamParser} using element definitions from this schema.
     * 
     * @param <T> The type of target values to be streamed by the created parser.
     * @param targetValueClass The {@link Class} object for the type of
     * {@linkplain XMLStreamParser#getTargetValueClass() target value} which will be streamed by the created parser.
     * @param targetContainerElementName The name of the {@linkplain #defineContainerElementWithChildBuilder(String)
     * element} which contains the specified target elements.
     * @param targetValueElementNames The name of the primary content elements whose target values will be streamed by
     * the created parser.
     * @return The newly created {@link SOAPStreamParser} instance.
     * @throws NoSuchElementException If a referenced element hasn't been defined in this schema.
     * @see #createSOAPParser(Class, String, String[])
     */
    public <@NonNull T> SOAPStreamParser<T> createSOAPParser(final Class<T> targetValueClass, final QName targetContainerElementName, final @NonNull QName... targetValueElementNames) throws NoSuchElementException {
      return new SOAPStreamParser<T>(targetValueClass, Collections.singleton(getParserOfParserType(EnvelopeElementParser.class, SOAP_1_2_ENVELOPE_QNAME)), getParserOfParserType(ContainerElementParser.class, targetContainerElementName), getParsersWithTargetType(targetValueClass, targetValueElementNames));
    }

    /**
     * Create a {@link SOAPStreamParser} using element definitions from this schema.
     * 
     * @param <T> The type of target values to be streamed by the created parser.
     * @param targetValueClass The {@link Class} object for the type of
     * {@linkplain XMLStreamParser#getTargetValueClass() target value} which will be streamed by the created parser.
     * @param targetContainerElementLocalName The {@linkplain QName#getLocalPart() local name} of the
     * {@linkplain #defineContainerElementWithChildBuilder(String) element} which contains the specified target elements
     * (the {@linkplain #getNamespace() current namespace} will be used).
     * @param targetElementLocalNames The {@linkplain QName#getLocalPart() local name} of the primary content elements
     * whose target values will be streamed by the created parser (the {@linkplain #getNamespace() current namespace}
     * will be used).
     * @return The newly created {@link SOAPStreamParser} instance.
     * @throws NoSuchElementException If a referenced element hasn't been defined in this schema.
     * @see #createSOAPParser(Class, QName, QName[])
     */
    public <@NonNull T> SOAPStreamParser<T> createSOAPParser(final Class<T> targetValueClass, final String targetContainerElementLocalName, final @NonNull String... targetElementLocalNames) throws NoSuchElementException {
      return createSOAPParser(targetValueClass, qn(targetContainerElementLocalName), qns(targetElementLocalNames));
    }

  } // SchemaBuilder

}
