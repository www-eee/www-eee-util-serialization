/*
 * Copyright 2017-2017 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU AFFERO GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, a copy of which you should have received in the file LICENSE.txt.
 */

package net.www_eee.util.serialization.xml;

import java.io.*;
import java.net.*;
import java.util.*;

import javax.xml.*;
import javax.xml.soap.*;
import javax.xml.stream.*;

import org.eclipse.jdt.annotation.*;


@NonNullByDefault
public interface XMLSerializable {

  /**
   * Write XML for this object to the supplied {@link XMLStreamWriter}.
   * 
   * @param streamWriter The {@link XMLStreamWriter} to write to.
   * @param parentNamespace The namespace {@link URI} currently set as default on the supplied {@link XMLStreamWriter}.
   * @throws XMLStreamException If there was a problem writing the XML.
   */
  public void writeXML(XMLStreamWriter streamWriter, @Nullable URI parentNamespace) throws XMLStreamException;

  /**
   * Create a {@link String} containing the {@linkplain #writeXML(XMLStreamWriter, URI) XML} for this object.
   * 
   * @return An XML {@link String}.
   */
  public default String toXMLString() {
    final StringWriter stringWriter = new StringWriter();
    try {
      writeXML(XMLOutputFactory.newInstance().createXMLStreamWriter(stringWriter), null);
    } catch (XMLStreamException | FactoryConfigurationError e) {
      throw new RuntimeException(e);
    }
    return stringWriter.getBuffer().toString();
  }

  public static XMLSerializable createElementWrapper(final XMLSerializable wrappedObject, final URI namespace, final String localName) {
    class ElementWrapper implements XMLSerializable, Serializable {

      @Override
      public void writeXML(final XMLStreamWriter streamWriter, final @Nullable URI parentNamespace) throws XMLStreamException {
        if (!namespace.equals(parentNamespace)) streamWriter.setDefaultNamespace(namespace.toString());
        streamWriter.writeStartElement(namespace.toString(), localName);
        if (!namespace.equals(parentNamespace)) streamWriter.writeDefaultNamespace(namespace.toString());
        wrappedObject.writeXML(streamWriter, namespace);
        streamWriter.writeEndElement();
        return;
      }

    };
    return new ElementWrapper();
  }

  public static XMLSerializable createSOAPFaultWrapper(final Throwable exception, final boolean isSenderError) {
    class SOAPFaultWrapper implements XMLSerializable, Serializable {
      private final URI soapNamespace = URI.create(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE);
      private final URI jaxwsNamespace = URI.create("http://jax-ws.dev.java.net/");

      private void writeJAXWSException(final Throwable exception, final XMLStreamWriter streamWriter, final String rootName, final @Nullable URI parentNamespace) throws XMLStreamException {
        if (!jaxwsNamespace.equals(parentNamespace)) streamWriter.setDefaultNamespace(jaxwsNamespace.toString());
        streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, rootName, jaxwsNamespace.toString());
        if (!jaxwsNamespace.equals(parentNamespace)) streamWriter.writeDefaultNamespace(jaxwsNamespace.toString());
        streamWriter.writeAttribute("class", exception.getClass().getName());

        if (exception.getMessage() != null) {
          streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, "message", jaxwsNamespace.toString());
          streamWriter.writeCharacters(Objects.requireNonNull(exception.getMessage()));
          streamWriter.writeEndElement();
        }

        final StackTraceElement[] stackTraceElements = exception.getStackTrace();
        if ((stackTraceElements != null) && (stackTraceElements.length > 0)) {
          streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, "stackTrace", jaxwsNamespace.toString());
          for (StackTraceElement stackTraceElement : stackTraceElements) {
            streamWriter.writeEmptyElement(XMLConstants.DEFAULT_NS_PREFIX, "frame", jaxwsNamespace.toString());
            if (stackTraceElement.getClassName() != null) streamWriter.writeAttribute("class", Objects.requireNonNull(stackTraceElement.getClassName()));
            if (stackTraceElement.getFileName() != null) streamWriter.writeAttribute("file", Objects.requireNonNull(stackTraceElement.getFileName()));
            if (stackTraceElement.getLineNumber() >= 0) streamWriter.writeAttribute("line", String.valueOf(stackTraceElement.getLineNumber()));
            if (stackTraceElement.getMethodName() != null) streamWriter.writeAttribute("method", Objects.requireNonNull(stackTraceElement.getMethodName()));
          }
          streamWriter.writeEndElement();
        }

        final Throwable cause = exception.getCause();
        if (cause != null) writeJAXWSException(cause, streamWriter, "cause", jaxwsNamespace);

        streamWriter.writeEndElement();
        return;
      }

      @Override
      public void writeXML(final XMLStreamWriter streamWriter, final @Nullable URI parentNamespace) throws XMLStreamException {
        if (!soapNamespace.equals(parentNamespace)) streamWriter.setDefaultNamespace(soapNamespace.toString());
        streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, "Fault", soapNamespace.toString());
        if (!soapNamespace.equals(parentNamespace)) streamWriter.writeDefaultNamespace(soapNamespace.toString());

        streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, "Code", soapNamespace.toString());
        streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, "Value", soapNamespace.toString());
        streamWriter.writeCharacters((isSenderError ? (SOAPConstants.SOAP_SENDER_FAULT.getPrefix() + ':' + SOAPConstants.SOAP_SENDER_FAULT.getLocalPart()) : (SOAPConstants.SOAP_RECEIVER_FAULT.getPrefix() + ':' + SOAPConstants.SOAP_RECEIVER_FAULT.getLocalPart())));
        streamWriter.writeEndElement();
        streamWriter.writeEndElement();

        streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, "Reason", soapNamespace.toString());
        streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, "Text", soapNamespace.toString());
        streamWriter.writeCharacters(exception.getClass().getName());
        if (exception.getMessage() != null) {
          streamWriter.writeCharacters(": ");
          streamWriter.writeCharacters(Objects.requireNonNull(exception.getMessage()));
        }
        streamWriter.writeEndElement();
        streamWriter.writeEndElement();

        streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, "Detail", soapNamespace.toString());
        writeJAXWSException(exception, streamWriter, "exception", soapNamespace);
        streamWriter.writeEndElement();

        streamWriter.writeEndElement();
        return;
      }

    };
    return new SOAPFaultWrapper();
  }

}
