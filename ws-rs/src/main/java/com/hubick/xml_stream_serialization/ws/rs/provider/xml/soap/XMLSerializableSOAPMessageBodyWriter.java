/*
 * Copyright 2017-2020 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU AFFERO GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, plus additional permissions, a copy of which you should have
 * received in the file LICENSE.txt.
 */

package com.hubick.xml_stream_serialization.ws.rs.provider.xml.soap;

import java.net.*;

import javax.xml.*;
import javax.xml.soap.*;
import javax.xml.stream.*;

import org.eclipse.jdt.annotation.*;

import com.hubick.xml_stream_serialization.ws.rs.provider.xml.*;
import com.hubick.xml_stream_serialization.xml.*;


/**
 * Extends {@link XMLSerializableMessageBodyWriter} to wrap the output in a
 * <a href="http://www.w3.org/TR/soap/">SOAP</a> Envelope+Body.
 */
@NonNullByDefault
public class XMLSerializableSOAPMessageBodyWriter extends XMLSerializableMessageBodyWriter {
  /**
   * The <a href="http://www.w3.org/">W3C</a> &quot;<a href="http://www.w3.org/TR/soap/">SOAP</a>&quot;
   * <a href="http://www.w3.org/TR/xml-names/">namespace</a> URI.
   */
  protected static final URI URI_NS_SOAP_1_2_ENVELOPE = URI.create(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE);

  /**
   * If you only want to write SOAP responses for some entities, like faults, and since you generally only want to
   * register a single writer for a given type, you can register this one (instead of
   * {@link XMLSerializableMessageBodyWriter}), and override this method to determine if you want to write SOAP for a
   * particular entity.
   * 
   * @param entity The entity to determine if a SOAP response should be written for.
   * @return If this writer should output a SOAP Body/Envelope wrapping the given entity (defaults to true).
   */
  protected boolean shouldWriteSOAPResponse(final XMLSerializable entity) {
    return true;
  };

  @Override
  protected void writeXML(final XMLSerializable entity, final XMLStreamWriter streamWriter, @Nullable URI parentNamespace) throws XMLStreamException {
    final boolean shouldWriteSOAPResponse = shouldWriteSOAPResponse(entity);
    if (shouldWriteSOAPResponse) {
      streamWriter.setDefaultNamespace(URI_NS_SOAP_1_2_ENVELOPE.toString());
      streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, "Envelope", URI_NS_SOAP_1_2_ENVELOPE.toString());
      streamWriter.writeDefaultNamespace(URI_NS_SOAP_1_2_ENVELOPE.toString());
      streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, "Body", URI_NS_SOAP_1_2_ENVELOPE.toString());
    }
    super.writeXML(entity, streamWriter, shouldWriteSOAPResponse ? URI_NS_SOAP_1_2_ENVELOPE : parentNamespace);
    if (shouldWriteSOAPResponse) {
      streamWriter.writeEndElement(); // Body
      streamWriter.writeEndElement(); // Envelope
    }
    return;
  }

}
