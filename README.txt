WWW-EEE Utilities - Serialization
---------------------------------

A Java library providing stream-based introspection/serialization/parsing of infinitely large objects using XML.


XMLSerializable
---------------

The XMLSerializable interface can be implemented by any object which is capable of streaming XML to an javax.xml.stream.XMLStreamWriter.

A JAX-RS MessageBodyWriter writer is provided for web services, which will automatically write out XML for any XMLSerializable object returned by your implementation.

SOAP functionality is also provided, including a MessageBodyWriter capable of writing out any XMLSerializable object within a SOAP Envelope, and an ExceptionMapper which will return SOAP Fault XML for any exceptions.


Introspectable
--------------

The Introspectable interface can be implemented by any object wishing to expose it's contents or some arbitrary set of properties.

It includes a builder implementation, which can be utilized by any implementation to compile the info-set containing it's properties.

The resulting info is XMLSerializable, meaning an object can implement Introspectable as an easy way of generating XML for itself.


XMLStreamParser
---------------

The XMLStreamParser class uses an XMLEventReader to parse XML documents, binding their contents to a stream of target value objects which are dynamically constructed according to instructions you provide.


Documentation
-------------

Please see the Javadoc on provided classes for more detailed documentation, as well as sample code under src/test/java/ within each module.

