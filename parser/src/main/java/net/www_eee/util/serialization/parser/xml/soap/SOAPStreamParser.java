/*
 * Copyright 2016-2017 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU LESSER GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, a copy of which you should have received in the file LICENSE.txt.
 */

package net.www_eee.util.serialization.parser.xml.soap;

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
  protected static final SOAPFactory SOAP_FACTORY;
  static {
    try {
      SOAP_FACTORY = SOAPFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL);
    } catch (SOAPException se) {
      throw new RuntimeException(se);
    }
  }
  protected static final TextElement<QName> VALUE_ELEMENT = new TextElement<>(QName.class, new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Value"), (s) -> new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, s.substring(s.indexOf(':') + 1), s.substring(0, s.indexOf(':'))), false);
  private static final WrapperElement<QName> CODE_ELEMENT = new WrapperElement<>(new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Code"), VALUE_ELEMENT);
  protected static final StringElement TEXT_ELEMENT = new StringElement(new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Text"), false);
  private static final WrapperElement<String> REASON_ELEMENT = new WrapperElement<>(new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Reason"), TEXT_ELEMENT);
  public static final Element<SOAPFaultException> FAULT_ELEMENT = new Element<>(SOAPFaultException.class, new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Fault"), (state, children) -> {
    final SOAPFault fault;
    try {
      fault = SOAP_FACTORY.createFault(firstValue(children, REASON_ELEMENT), firstValue(children, CODE_ELEMENT));
    } catch (SOAPException soape) {
      throw new RuntimeException(soape);
    }
    throw new SOAPFaultException(fault);
  }, false, CODE_ELEMENT, REASON_ELEMENT);

  public SOAPStreamParser(final EnvelopeElement envelopeParser, final Element<T> targetParser) {
    super(envelopeParser, targetParser);
    //TODO return; // https://bugs.openjdk.java.net/browse/JDK-8036775
  }

  public static class HeaderElement extends ContainerElement {

    public HeaderElement(final Collection<Parser<?,?>> childParsers) {
      super(new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Header"), childParsers);
      return;
    }

    public HeaderElement(final @NonNull Parser<?,?>... childParsers) {
      this((childParsers != null) ? Arrays.asList(childParsers) : Collections.emptyList());
      return;
    }

    public HeaderElement(final Parser<?,?> childParser) {
      this(Collections.singleton(childParser));
      return;
    }

  } // HeaderElement

  public static class BodyElement extends ContainerElement {

    public BodyElement(final Element<?> responseParser) {
      super(new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Body"), FAULT_ELEMENT, responseParser);
      return;
    }

  } // BodyElement

  public static class EnvelopeElement extends ContainerElement {

    public EnvelopeElement(final @Nullable HeaderElement headerParser, final BodyElement bodyParser) {
      super(new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Envelope"), (headerParser != null) ? Arrays.asList(headerParser, bodyParser) : Collections.singleton(bodyParser));
      return;
    }

  } // EnvelopeElement

}
