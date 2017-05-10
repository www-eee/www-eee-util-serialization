/*
 * Copyright 2016-2017 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU LESSER GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, a copy of which you should have received in the file LICENSE.txt.
 */

package net.www_eee.util.serialization.parser.xml.soap;

import java.net.*;
import java.util.*;

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
  public static final QName ENVELOPE_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Envelope");
  public static final QName HEADER_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Header");
  public static final QName BODY_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Body");
  public static final QName VALUE_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Value");
  public static final QName CODE_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Code");
  public static final QName TEXT_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Text");
  public static final QName REASON_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Reason");
  public static final QName FAULT_QNAME = new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Fault");
  protected static final SOAPFactory SOAP_FACTORY;
  static {
    try {
      SOAP_FACTORY = SOAPFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL);
    } catch (SOAPException se) {
      throw new RuntimeException(se);
    }
  }
  protected static final SimpleElementParser<QName> VALUE_ELEMENT = new SimpleElementParser<>(QName.class, VALUE_QNAME, (s) -> new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, s.substring(s.indexOf(':') + 1), s.substring(0, s.indexOf(':'))), false);
  private static final WrapperElementParser<QName> CODE_ELEMENT = new WrapperElementParser<>(CODE_QNAME, VALUE_ELEMENT);
  protected static final StringElementParser TEXT_ELEMENT = new StringElementParser(TEXT_QNAME, false);
  private static final WrapperElementParser<String> REASON_ELEMENT = new WrapperElementParser<>(REASON_QNAME, TEXT_ELEMENT);
  protected static final ElementParser<SOAPFaultException> FAULT_ELEMENT = new ElementParser<>(SOAPFaultException.class, FAULT_QNAME, (ctx) -> {
    final SOAPFault fault;
    try {
      fault = SOAP_FACTORY.createFault(cast(ctx).getRequiredChildValue(REASON_ELEMENT), cast(ctx).getRequiredChildValue(CODE_ELEMENT));
    } catch (SOAPException soape) {
      throw new RuntimeException(soape);
    }
    throw new SOAPFaultException(fault);
  }, false, CODE_ELEMENT, REASON_ELEMENT);

  protected SOAPStreamParser(final Class<T> targetValueClass, final EnvelopeElementParser envelopeParser, final ElementParser<T> targetParser) {
    super(targetValueClass, envelopeParser, targetParser);
    //TODO return; // https://bugs.openjdk.java.net/browse/JDK-8036775
  }

  /**
   * Create a SOAP {@link SchemaBuilder SchemaBuilder} which can then be used to define the elements used within the XML
   * documents you wish to {@link SchemaBuilder#createParser(Class, QName) create a parser} for.
   * 
   * @param namespace The (optional) namespace used by your XML.
   * @return A new {@link SchemaBuilder}.
   */
  @SuppressWarnings("unchecked")
  public static SchemaBuilder<@NonNull ? extends SchemaBuilder<@NonNull ?>> buildSchema(final @Nullable URI namespace) {
    return new SchemaBuilder<>((Class<SchemaBuilder<?>>)(Object)SchemaBuilder.class, namespace, null, false);
  }

  protected static class HeaderElementParser extends ContainerElementParser {

    public HeaderElementParser(final @NonNull ElementParser<?> @Nullable... childParsers) {
      super(HEADER_QNAME, childParsers);
      return;
    }

  } // HeaderElementParser

  protected static class BodyElementParser extends ContainerElementParser {

    public BodyElementParser(final ElementParser<?> childElementParser) {
      super(BODY_QNAME, FAULT_ELEMENT, childElementParser);
      return;
    }

  } // BodyElementParser

  protected static class EnvelopeElementParser extends ContainerElementParser {

    public EnvelopeElementParser(final HeaderElementParser headerParser, final BodyElementParser bodyParser) {
      super(ENVELOPE_QNAME, headerParser, bodyParser);
      return;
    }

    public EnvelopeElementParser(final BodyElementParser bodyParser) {
      super(ENVELOPE_QNAME, bodyParser);
      return;
    }

  } // EnvelopeElementParser

  /**
   * An extended {@link net.www_eee.util.serialization.parser.xml.XMLStreamParser.SchemaBuilder SchemaBuilder}, adding
   * support for the definition of SOAP {@link #defineHeaderElementWithChildBuilder() Header},
   * {@link #defineBodyElement(QName, Class) Body}, and {@link #defineEnvelopeElement(boolean) Envelope} elements.
   * 
   * @param <SB> The concrete class of schema builder being used.
   * @see net.www_eee.util.serialization.parser.xml.XMLStreamParser.SchemaBuilder
   */
  public static class SchemaBuilder<@NonNull SB extends SchemaBuilder<@NonNull ?>> extends XMLStreamParser.SchemaBuilder<SB> {

    protected SchemaBuilder(final Class<? extends SB> builderType, final @Nullable URI namespace, final @Nullable Set<ElementParser<?>> elementParsers, final boolean unmodifiable) {
      super(builderType, namespace, elementParsers, unmodifiable);
      this.elementParsers.add(FAULT_ELEMENT);
      return;
    }

    @Override
    protected SB forkImpl(final @Nullable URI namespace, final boolean unmodifiable) {
      return schemaBuilderType.cast(new SchemaBuilder<SB>(schemaBuilderType, namespace, elementParsers, unmodifiable));
    }

    public final ChildElementListBuilder<@NonNull ?> defineHeaderElementWithChildBuilder() {
      return new ChildElementListBuilder<ElementParser<?>>(ElementParser.WILDCARD_CLASS, (childParsers) -> addParser(new HeaderElementParser(childParsers)));
    }

    public final <@NonNull CT> SB defineBodyElement(final QName childElementName, final Class<CT> childElementTargetValueClass) throws NoSuchElementException {
      return addParser(new BodyElementParser(getParser(childElementName, childElementTargetValueClass)));
    }

    public final SB defineBodyElement(final QName childElementName) throws NoSuchElementException {
      return addParser(new BodyElementParser(getParser(childElementName)));
    }

    public final SB defineBodyElement(final String childElementName) throws NoSuchElementException {
      return defineBodyElement(qn(childElementName));
    }

    public final SB defineEnvelopeElement(final boolean hasHeader) throws NoSuchElementException {
      return addParser(hasHeader ? new EnvelopeElementParser(getParser(HeaderElementParser.class, HEADER_QNAME), getParser(BodyElementParser.class, BODY_QNAME)) : new EnvelopeElementParser(getParser(BodyElementParser.class, BODY_QNAME)));
    }

    @Override
    public <@NonNull T> SOAPStreamParser<T> createParser(final Class<T> targetValueClass, final QName documentElementName, final QName targetElementName) throws NoSuchElementException {
      return new SOAPStreamParser<T>(targetValueClass, getParser(EnvelopeElementParser.class, documentElementName), getParser(targetElementName, targetValueClass));
    }

    public <@NonNull T> SOAPStreamParser<T> createParser(final Class<T> targetValueClass, final QName targetElementName) throws NoSuchElementException {
      return new SOAPStreamParser<T>(targetValueClass, getParser(EnvelopeElementParser.class, ENVELOPE_QNAME), getParser(targetElementName, targetValueClass));
    }

    public <@NonNull T> SOAPStreamParser<T> createParser(final Class<T> targetValueClass, final String targetElementName) throws NoSuchElementException {
      return createParser(targetValueClass, qn(targetElementName));
    }

  } // SchemaBuilder

}
