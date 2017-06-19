/*
 * Copyright 2016-2017 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU AFFERO GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, plus additional permissions, a copy of which you should have
 * received in the file LICENSE.txt.
 */

package net.www_eee.util.serialization.parser.xml.soap;

import java.net.*;
import java.util.*;
import java.util.stream.*;

import javax.xml.namespace.*;
import javax.xml.soap.*;
import javax.xml.ws.soap.*;

import org.eclipse.jdt.annotation.*;

import net.www_eee.util.serialization.parser.xml.*;


/**
 * An {@link XMLStreamParser} with additional support for {@linkplain #buildSchema(URI) defining}
 * {@linkplain SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} elements.
 *
 * @param <T> The type of target values to be streamed.
 */
@NonNullByDefault
public class SOAPStreamParser<@NonNull T> extends XMLStreamParser<T> {
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Envelope</code> element.
   */
  public static final QName ENVELOPE_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Envelope");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Header</code> element.
   */
  public static final QName HEADER_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Header");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Body</code> element.
   */
  public static final QName BODY_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Body");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Value</code> element.
   */
  public static final QName VALUE_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Value");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Code</code> element.
   */
  public static final QName CODE_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Code");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Text</code> element.
   */
  public static final QName TEXT_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Text");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Reason</code> element.
   */
  public static final QName REASON_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Reason");
  /**
   * A {@link QName} constant for the {@link SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP} <code>Fault</code> element.
   */
  public static final QName FAULT_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Fault");
  protected static final SOAPFactory SOAP_FACTORY;
  static {
    try {
      SOAP_FACTORY = SOAPFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL);
    } catch (SOAPException se) {
      throw new RuntimeException(se);
    }
  }
  protected static final SimpleElementParser<QName> VALUE_ELEMENT_PARSER = new SimpleElementParser<>(QName.class, VALUE_QNAME, (s) -> new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, s.substring(s.indexOf(':') + 1), s.substring(0, s.indexOf(':'))), false);
  private static final WrapperElementParser<QName> CODE_ELEMENT_PARSER = new WrapperElementParser<>(CODE_QNAME, null, VALUE_ELEMENT_PARSER);
  protected static final StringElementParser TEXT_ELEMENT_PARSER = new StringElementParser(TEXT_QNAME, false);
  private static final WrapperElementParser<String> REASON_ELEMENT_PARSER = new WrapperElementParser<>(REASON_QNAME, null, TEXT_ELEMENT_PARSER);
  protected static final ElementParser<SOAPFaultException> FAULT_ELEMENT_PARSER = new ElementParser<>(SOAPFaultException.class, FAULT_QNAME, (ctx) -> {
    final SOAPFault fault;
    try {
      fault = SOAP_FACTORY.createFault(cast(ctx).getRequiredChildValue(REASON_ELEMENT_PARSER), cast(ctx).getRequiredChildValue(CODE_ELEMENT_PARSER));
    } catch (SOAPException soape) {
      throw ctx.createElementValueException(soape);
    }
    return new SOAPFaultException(fault);
  }, false, null, CODE_ELEMENT_PARSER, REASON_ELEMENT_PARSER);

  @SafeVarargs
  protected SOAPStreamParser(final Class<T> targetValueClass, final Set<EnvelopeElementParser> envelopeParsers, final ContainerElementParser targetContainerElementParser, final @NonNull ElementParser<? extends T>... targetValueParsers) {
    super(targetValueClass, envelopeParsers, targetContainerElementParser, targetValueParsers);
    //TODO return; // https://bugs.openjdk.java.net/browse/JDK-8036775
  }

  /**
   * Create a SOAP {@link SchemaBuilder SchemaBuilder} which can then be used to define the elements used within the XML
   * documents you wish to {@link SchemaBuilder#createSOAPParser(Class, QName, QName[]) create a parser} for.
   * 
   * @param namespace The (optional) namespace used by your XML.
   * @return A new {@link SchemaBuilder}.
   */
  @SuppressWarnings("unchecked")
  public static SchemaBuilder<@NonNull ? extends SchemaBuilder<@NonNull ?>> buildSchema(final @Nullable URI namespace) {
    return new SchemaBuilder<>((Class<SchemaBuilder<?>>)(Object)SchemaBuilder.class, namespace, null, false);
  }

  protected static class HeaderElementParser extends ContainerElementParser {

    public HeaderElementParser(final @Nullable Set<? extends ElementParser<? extends Exception>> childExceptionParsers, final @NonNull ElementParser<?> @Nullable... childValueParsers) {
      super(HEADER_QNAME, childExceptionParsers, childValueParsers);
      return;
    }

  } // HeaderElementParser

  protected static class BodyElementParser extends ContainerElementParser {

    public BodyElementParser(final @Nullable Set<? extends ElementParser<? extends Exception>> childExceptionParsers, final @NonNull ElementParser<?> @Nullable... childValueParsers) {
      super(BODY_QNAME, childExceptionParsers, childValueParsers);
      return;
    }

  } // BodyElementParser

  protected static class EnvelopeElementParser extends ContainerElementParser {

    public EnvelopeElementParser(final HeaderElementParser headerParser, final BodyElementParser bodyParser) {
      super(ENVELOPE_QNAME, null, headerParser, bodyParser);
      return;
    }

    public EnvelopeElementParser(final BodyElementParser bodyParser) {
      super(ENVELOPE_QNAME, null, bodyParser);
      return;
    }

  } // EnvelopeElementParser

  /**
   * An extension of the regular {@link net.www_eee.util.serialization.parser.xml.XMLStreamParser.SchemaBuilder
   * SchemaBuilder} class, adding in additional support for the definition of SOAP
   * {@link #defineHeaderElementWithChildBuilder() Header}, {@link #defineBodyElement(Class, QName) Body}, and
   * {@link #defineEnvelopeElement(boolean) Envelope} elements.
   * 
   * @param <SB> The concrete class of schema builder being used.
   * @see net.www_eee.util.serialization.parser.xml.XMLStreamParser.SchemaBuilder
   */
  public static class SchemaBuilder<@NonNull SB extends SchemaBuilder<@NonNull ?>> extends XMLStreamParser.SchemaBuilder<SB> {

    protected SchemaBuilder(final Class<? extends SB> builderType, final @Nullable URI namespace, final @Nullable Set<ElementParser<?>> elementParsers, final boolean unmodifiable) {
      super(builderType, namespace, elementParsers, unmodifiable);
      this.elementParsers.add(FAULT_ELEMENT_PARSER);
      return;
    }

    @Override
    protected SB forkImpl(final @Nullable URI namespace, final boolean unmodifiable) {
      return Objects.requireNonNull(schemaBuilderType.cast(new SchemaBuilder<SB>(schemaBuilderType, namespace, elementParsers, unmodifiable)));
    }

    /**
     * Define a <code>Header</code> element (a specialized {@linkplain #defineContainerElementWithChildBuilder(String)
     * container}).
     * 
     * @return A {@link net.www_eee.util.serialization.parser.xml.XMLStreamParser.SchemaBuilder.ChildElementListBuilder
     * ChildElementListBuilder} which you can use to define which elements the <code>Header</code> will have as
     * children.
     */
    public final ChildElementListBuilder defineHeaderElementWithChildBuilder() {
      return new ChildElementListBuilder(Collections.singleton(FAULT_ELEMENT_PARSER), null) {

        @Override
        public SB completeDefinition() {
          addParser(new HeaderElementParser(childExceptionParsers, (!childValueParsers.isEmpty()) ? childValueParsers.stream().toArray((n) -> new ElementParser<?>[n]) : null));
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
      return addParser(new BodyElementParser(Collections.singleton(FAULT_ELEMENT_PARSER), getParserWithTargetType(childElementTargetValueClass, childElementName)));
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
      return addParser(new BodyElementParser(Collections.singleton(FAULT_ELEMENT_PARSER), getParser(childElementName)));
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
     * @return A {@link net.www_eee.util.serialization.parser.xml.XMLStreamParser.SchemaBuilder.ChildElementListBuilder
     * ChildElementListBuilder} which you can use to define which elements the <code>Body</code> will have as children.
     */
    public final ChildElementListBuilder defineBodyElementWithChildBuilder() {
      return new ChildElementListBuilder(Collections.singleton(FAULT_ELEMENT_PARSER), null) {

        @Override
        public SB completeDefinition() {
          addParser(new BodyElementParser(childExceptionParsers, (!childValueParsers.isEmpty()) ? childValueParsers.stream().toArray((n) -> new ElementParser<?>[n]) : null));
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
      return addParser(hasHeader ? new EnvelopeElementParser(getParserOfParserType(HeaderElementParser.class, HEADER_QNAME), getParserOfParserType(BodyElementParser.class, BODY_QNAME)) : new EnvelopeElementParser(getParserOfParserType(BodyElementParser.class, BODY_QNAME)));
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
      return new SOAPStreamParser<T>(targetValueClass, Collections.singleton(getParserOfParserType(EnvelopeElementParser.class, ENVELOPE_QNAME)), getParserOfParserType(ContainerElementParser.class, targetContainerElementName), getParsersWithTargetType(targetValueClass, targetValueElementNames));
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
