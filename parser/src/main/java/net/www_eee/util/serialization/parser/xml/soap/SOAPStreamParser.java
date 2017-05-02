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
 * An {@link XMLStreamParser} subclass specialized for parsing {@linkplain SOAPConstants#URI_NS_SOAP_1_2_ENVELOPE SOAP
 * 1.2}.
 *
 * @param <T> The type of target objects to be streamed.
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
  protected static final TextElementParser<QName> VALUE_ELEMENT = new TextElementParser<>(QName.class, VALUE_QNAME, (s) -> new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, s.substring(s.indexOf(':') + 1), s.substring(0, s.indexOf(':'))), false);
  private static final WrapperElementParser<QName> CODE_ELEMENT = new WrapperElementParser<>(CODE_QNAME, VALUE_ELEMENT);
  protected static final StringElementParser TEXT_ELEMENT = new StringElementParser(TEXT_QNAME, false);
  private static final WrapperElementParser<String> REASON_ELEMENT = new WrapperElementParser<>(REASON_QNAME, TEXT_ELEMENT);
  protected static final ElementParser<SOAPFaultException> FAULT_ELEMENT = new ElementParser<>(SOAPFaultException.class, FAULT_QNAME, (ctx) -> {
    final SOAPFault fault;
    try {
      fault = SOAP_FACTORY.createFault(cast(ctx).child(REASON_ELEMENT), cast(ctx).child(CODE_ELEMENT));
    } catch (SOAPException soape) {
      throw new RuntimeException(soape);
    }
    throw new SOAPFaultException(fault);
  }, false, CODE_ELEMENT, REASON_ELEMENT);

  protected SOAPStreamParser(final Class<T> targetClass, final Collection<ElementParser<?>> elementParsers, final QName targetElementName) throws NoSuchElementException, ClassCastException {
    super(targetClass, elementParsers, ENVELOPE_QNAME, targetElementName);
    //TODO return; // https://bugs.openjdk.java.net/browse/JDK-8036775
  }

  @SuppressWarnings("unchecked")
  public static SchemaBuilder<? extends SchemaBuilder<?>> create(final @Nullable URI namespace) {
    return new SchemaBuilder<>((Class<SchemaBuilder<?>>)(Object)SchemaBuilder.class, namespace, null);
  }

  protected static class HeaderElementParser extends ContainerElementParser {

    public HeaderElementParser(final @NonNull ContentParser<?,?> @Nullable... childParsers) {
      super(HEADER_QNAME, childParsers);
      return;
    }

  } // HeaderElementParser

  protected static class BodyElementParser extends ContainerElementParser {

    public BodyElementParser(final ElementParser<?> childParser) {
      super(BODY_QNAME, FAULT_ELEMENT, childParser);
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

  public static class SchemaBuilder<@NonNull SB extends SchemaBuilder<?>> extends XMLStreamParser.SchemaBuilder<SB> {

    protected SchemaBuilder(final Class<? extends SB> builderType, final @Nullable URI namespace, final @Nullable Map<QName,ElementParser<?>> elementParsers) {
      super(builderType, namespace, elementParsers);
      add(FAULT_ELEMENT);
      return;
    }

    @Override
    protected SB forkImpl(final @Nullable URI namespace) {
      return builderType.cast(new SchemaBuilder<SB>(builderType, namespace, elementParsers));
    }

    public final SB header(final @NonNull QName... childElementNames) throws NoSuchElementException, ClassCastException {
      return add(new HeaderElementParser(getParsers(childElementNames)));
    }

    public final SB header(final @NonNull String @Nullable... childElementNames) throws NoSuchElementException, ClassCastException {
      return header(qns(childElementNames));
    }

    public final SB body(final QName childElementName) throws NoSuchElementException, ClassCastException {
      return add(new BodyElementParser(getParser(ElementParser.class, childElementName)));
    }

    public final SB body(final String childElementName) throws NoSuchElementException, ClassCastException {
      return body(qn(childElementName));
    }

    public final SB envelope(final boolean header) throws NoSuchElementException, ClassCastException {
      return add(header ? new EnvelopeElementParser(getParser(HeaderElementParser.class, HEADER_QNAME), getParser(BodyElementParser.class, BODY_QNAME)) : new EnvelopeElementParser(getParser(BodyElementParser.class, BODY_QNAME)));
    }

    @Override
    public <@NonNull T> SOAPStreamParser<T> parser(final Class<T> targetClass, final QName documentElementName, final QName targetElementName) throws NoSuchElementException, ClassCastException {
      return new SOAPStreamParser<T>(targetClass, elementParsers.values(), targetElementName);
    }

    public <@NonNull T> SOAPStreamParser<T> parser(final Class<T> targetClass, final QName targetElementName) throws NoSuchElementException, ClassCastException {
      return new SOAPStreamParser<T>(targetClass, elementParsers.values(), targetElementName);
    }

    public <@NonNull T> SOAPStreamParser<T> parser(final Class<T> targetClass, final String targetElementName) throws NoSuchElementException, ClassCastException {
      return parser(targetClass, qn(targetElementName));
    }

  } // SchemaBuilder

}
