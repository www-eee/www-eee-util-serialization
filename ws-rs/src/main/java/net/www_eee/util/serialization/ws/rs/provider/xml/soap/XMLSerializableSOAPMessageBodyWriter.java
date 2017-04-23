/*
 * Copyright 2017-2017 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU AFFERO GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, a copy of which you should have received in the file LICENSE.txt.
 */

package net.www_eee.util.serialization.ws.rs.provider.xml.soap;

import java.net.*;

import javax.xml.*;
import javax.xml.stream.*;

import org.eclipse.jdt.annotation.*;

import net.www_eee.util.serialization.xml.*;

import net.www_eee.util.serialization.ws.rs.provider.xml.*;


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
  protected static final URI SOAP_ENV_NS_URI = URI.create("http://www.w3.org/2003/05/soap-envelope");

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
      streamWriter.setDefaultNamespace(SOAP_ENV_NS_URI.toString());
      streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, "Envelope", SOAP_ENV_NS_URI.toString());
      streamWriter.writeDefaultNamespace(SOAP_ENV_NS_URI.toString());
      streamWriter.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, "Body", SOAP_ENV_NS_URI.toString());
    }
    super.writeXML(entity, streamWriter, shouldWriteSOAPResponse ? SOAP_ENV_NS_URI : parentNamespace);
    if (shouldWriteSOAPResponse) {
      streamWriter.writeEndElement(); // Body
      streamWriter.writeEndElement(); // Envelope
    }
    return;
  }

}
